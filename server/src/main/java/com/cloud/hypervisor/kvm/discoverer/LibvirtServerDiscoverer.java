// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.kvm.discoverer;

import static com.cloud.configuration.ConfigurationManagerImpl.ADD_HOST_ON_SERVICE_RESTART_KVM;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.agent.lb.IndirectAgentLB;
import org.apache.cloudstack.ca.CAManager;
import org.apache.cloudstack.ca.SetupCertificateCommand;
import org.apache.cloudstack.direct.download.DirectDownloadManager;
import org.apache.cloudstack.framework.ca.Certificate;
import org.apache.cloudstack.utils.cache.LazyCache;
import org.apache.cloudstack.utils.security.KeyStoreUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.Listener;
import com.cloud.agent.api.AgentControlAnswer;
import com.cloud.agent.api.AgentControlCommand;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.ShutdownCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.configuration.Config;
import com.cloud.dc.ClusterVO;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.DiscoveredWithErrorException;
import com.cloud.exception.DiscoveryException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.host.HostInfo;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.PhysicalNetworkSetupInfo;
import com.cloud.resource.Discoverer;
import com.cloud.resource.DiscovererBase;
import com.cloud.resource.ResourceStateAdapter;
import com.cloud.resource.ServerResource;
import com.cloud.resource.UnableDeleteHostException;
import com.cloud.utils.PasswordGenerator;
import com.cloud.utils.StringUtils;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.ssh.SSHCmdHelper;
import com.trilead.ssh2.Connection;


public abstract class LibvirtServerDiscoverer extends DiscovererBase implements Discoverer, Listener, ResourceStateAdapter {
    private final int _waitTime = 5; /* wait for 5 minutes */

    private final static HashSet<String> COMPATIBLE_HOST_OSES = new HashSet<>(Arrays.asList("Rocky", "Rocky Linux",
            "Red", "Red Hat Enterprise Linux", "Oracle", "Oracle Linux Server", "AlmaLinux"));

    private String _kvmPrivateNic;
    private String _kvmPublicNic;
    private String _kvmGuestNic;
    @Inject
    private AgentManager agentMgr;
    @Inject
    private CAManager caManager;
    @Inject
    DirectDownloadManager directDownloadManager;
    @Inject
    private IndirectAgentLB indirectAgentLB;
    @Inject
    private HostDao hostDao;

    private LazyCache<Long, HostVO> clusterExistingHostCache;

    private HostVO getExistingHostForCluster(long clusterId) {
        HostVO existingHostInCluster = _hostDao.findAnyStateHypervisorHostInCluster(clusterId);
        if (existingHostInCluster != null) {
            _hostDao.loadDetails(existingHostInCluster);
        }
        return existingHostInCluster;
    }

    @Override
    public abstract Hypervisor.HypervisorType getHypervisorType();

    @Override
    public boolean processAnswers(long agentId, long seq, Answer[] answers) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean processCommands(long agentId, long seq, Command[] commands) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public AgentControlAnswer processControlCommand(long agentId, AgentControlCommand cmd) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void processHostAdded(long hostId) {
        HostVO host = hostDao.findById(hostId);
        if (host != null && getHypervisorType().equals(host.getHypervisorType())) {
            directDownloadManager.syncCertificatesToHost(hostId, host.getDataCenterId());
        }
    }

    @Override
    public void processConnect(Host host, StartupCommand cmd, boolean forRebalance) {
    }

    @Override
    public boolean processDisconnect(long agentId, Status state) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void processHostAboutToBeRemoved(long hostId) {
    }

    @Override
    public void processHostRemoved(long hostId, long clusterId) {
    }

    @Override
    public boolean isRecurring() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public int getTimeout() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public boolean processTimeout(long agentId, long seq) {
        // TODO Auto-generated method stub
        return false;
    }

    private void setupAgentSecurity(final Connection sshConnection, final String agentIp, final String agentHostname) {
        if (sshConnection == null) {
            throw new CloudRuntimeException("Cannot secure agent communication because ssh connection is invalid for host ip=" + agentIp);
        }

        Integer validityPeriod = CAManager.CertValidityPeriod.value();
        if (validityPeriod < 1) {
            validityPeriod = 1;
        }

        String keystorePassword = PasswordGenerator.generateRandomPassword(16);
        final SSHCmdHelper.SSHCmdResult keystoreSetupResult = SSHCmdHelper.sshExecuteCmdWithResult(sshConnection,
                String.format("sudo /usr/share/cloudstack-common/scripts/util/%s " +
                                "/etc/cloudstack/agent/agent.properties " +
                                "/etc/cloudstack/agent/%s " +
                                "%s %d " +
                                "/etc/cloudstack/agent/%s",
                        KeyStoreUtils.KS_SETUP_SCRIPT,
                        KeyStoreUtils.KS_FILENAME,
                        keystorePassword,
                        validityPeriod,
                        KeyStoreUtils.CSR_FILENAME));

        if (!keystoreSetupResult.isSuccess()) {
            throw new CloudRuntimeException("Failed to setup keystore on the KVM host: " + agentIp);
        }

        final Certificate certificate = caManager.issueCertificate(keystoreSetupResult.getStdOut(), Arrays.asList(agentHostname, agentIp), Collections.singletonList(agentIp), null, null);
        if (certificate == null || certificate.getClientCertificate() == null) {
            throw new CloudRuntimeException("Failed to issue certificates for KVM host agent: " + agentIp);
        }

        final SetupCertificateCommand certificateCommand = new SetupCertificateCommand(certificate);
        final SSHCmdHelper.SSHCmdResult setupCertResult = SSHCmdHelper.sshExecuteCmdWithResult(sshConnection,
                String.format("sudo /usr/share/cloudstack-common/scripts/util/%s " +
                                "/etc/cloudstack/agent/agent.properties %s " +
                                "/etc/cloudstack/agent/%s %s " +
                                "/etc/cloudstack/agent/%s \"%s\" " +
                                "/etc/cloudstack/agent/%s \"%s\" " +
                                "/etc/cloudstack/agent/%s \"%s\"",
                        KeyStoreUtils.KS_IMPORT_SCRIPT,
                        keystorePassword,
                        KeyStoreUtils.KS_FILENAME,
                        KeyStoreUtils.SSH_MODE,
                        KeyStoreUtils.CERT_FILENAME,
                        certificateCommand.getEncodedCertificate(),
                        KeyStoreUtils.CACERT_FILENAME,
                        certificateCommand.getEncodedCaCertificates(),
                        KeyStoreUtils.PKEY_FILENAME,
                        certificateCommand.getEncodedPrivateKey()));

        if (setupCertResult != null && !setupCertResult.isSuccess()) {
            throw new CloudRuntimeException("Failed to setup certificate in the KVM agent's keystore file, please see logs and configure manually!");
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Succeeded to import certificate in the keystore for agent on the KVM host: " + agentIp + ". Agent secured and trusted.");
        }
    }

    @Override
    public Map<? extends ServerResource, Map<String, String>>
        find(long dcId, Long podId, Long clusterId, URI uri, String username, String password, List<String> hostTags) throws DiscoveryException {
        ClusterVO cluster = _clusterDao.findById(clusterId);
        if (cluster == null || cluster.getHypervisorType() != getHypervisorType()) {
            if (logger.isInfoEnabled())
                logger.info("invalid cluster id or cluster is not for " + getHypervisorType() + " hypervisors");
            return null;
        }

        // Set cluster GUID based on cluster ID if null
        if (cluster.getGuid() == null) {
            cluster.setGuid(UUID.nameUUIDFromBytes(String.valueOf(clusterId).getBytes()).toString());
            _clusterDao.update(clusterId, cluster);
        }

        Map<KvmDummyResourceBase, Map<String, String>> resources = new HashMap<KvmDummyResourceBase, Map<String, String>>();
        Map<String, String> details = new HashMap<String, String>();
        if (!uri.getScheme().equals("http")) {
            String msg = "urlString is not http so we're not taking care of the discovery for this: " + uri;
            logger.debug(msg);
            return null;
        }
        Connection sshConnection = null;
        String agentIp = null;
        try {

            String hostname = uri.getHost();
            InetAddress ia = InetAddress.getByName(hostname);
            agentIp = ia.getHostAddress();
            String guid = UUID.nameUUIDFromBytes(agentIp.getBytes()).toString();

            List<HostVO> existingHosts = _resourceMgr.listAllHostsInOneZoneByType(Host.Type.Routing, dcId);
            if (existingHosts != null) {
                for (HostVO existingHost : existingHosts) {
                    if (existingHost.getGuid().toLowerCase().startsWith(guid.toLowerCase())) {
                        final String msg = "Skipping host " + agentIp + " because " + guid + " is already in the database for resource " + existingHost.getGuid() + " with ID " + existingHost.getUuid();
                        logger.debug(msg);
                        throw new CloudRuntimeException(msg);
                    }
                }
            }

            sshConnection = new Connection(agentIp, 22);

            sshConnection.connect(null, 60000, 60000);

            final String privateKey = _configDao.getValue("ssh.privatekey");
            if (!SSHCmdHelper.acquireAuthorizedConnectionWithPublicKey(sshConnection, username, privateKey)) {
                if (org.apache.commons.lang3.StringUtils.isEmpty(password)) {
                    logger.error("Failed to authenticate with ssh key");
                    throw new DiscoveredWithErrorException("Authentication error with ssh private key");
                }
                logger.info("Failed to authenticate with ssh key, retrying with password");
                if (!sshConnection.authenticateWithPassword(username, password)) {
                    logger.error("Failed to authenticate with password");
                    throw new DiscoveredWithErrorException("Authentication error with host password");
                }
            }

            if (!SSHCmdHelper.sshExecuteCmd(sshConnection, "ls /dev/kvm")) {
                String errorMsg = "This machine does not have KVM enabled.";
                if (logger.isDebugEnabled()) {
                    logger.debug(errorMsg);
                }
                throw new DiscoveredWithErrorException(errorMsg);
            }

            List<PhysicalNetworkSetupInfo> netInfos = _networkMgr.getPhysicalNetworkInfo(dcId, getHypervisorType());
            String kvmPrivateNic = null;
            String kvmPublicNic = null;
            String kvmGuestNic = null;

            for (PhysicalNetworkSetupInfo info : netInfos) {
                if (info.getPrivateNetworkName() != null) {
                    kvmPrivateNic = info.getPrivateNetworkName();
                }
                if (info.getPublicNetworkName() != null) {
                    kvmPublicNic = info.getPublicNetworkName();
                }
                if (info.getGuestNetworkName() != null) {
                    kvmGuestNic = info.getGuestNetworkName();
                }
            }

            if (kvmPrivateNic == null && kvmPublicNic == null && kvmGuestNic == null) {
                kvmPrivateNic = _kvmPrivateNic;
                kvmPublicNic = _kvmPublicNic;
                kvmGuestNic = _kvmGuestNic;
            }

            if (kvmPublicNic == null) {
                kvmPublicNic = (kvmGuestNic != null) ? kvmGuestNic : kvmPrivateNic;
            }

            if (kvmPrivateNic == null) {
                kvmPrivateNic = (kvmPublicNic != null) ? kvmPublicNic : kvmGuestNic;
            }

            if (kvmGuestNic == null) {
                kvmGuestNic = (kvmPublicNic != null) ? kvmPublicNic : kvmPrivateNic;
            }

            if (!caManager.canProvisionCertificates()) {
                throw new CloudRuntimeException("Configured CA plugin cannot provision X509 certificate(s), failing to add host due to security insufficiency.");
            }

            setupAgentSecurity(sshConnection, agentIp, hostname);

            String parameters = " -m " + StringUtils.toCSVList(indirectAgentLB.getManagementServerList(null, dcId, null)) + " -z " + dcId + " -p " + podId     + " -c " + clusterId + " -g " + guid + " -a -s ";

            parameters += " --pubNic=" + kvmPublicNic;
            parameters += " --prvNic=" + kvmPrivateNic;
            parameters += " --guestNic=" + kvmGuestNic;
            parameters += " --hypervisor=" + cluster.getHypervisorType().toString().toLowerCase();

            String setupAgentCommand = "cloudstack-setup-agent ";
            if (!username.equals("root")) {
                setupAgentCommand = "sudo cloudstack-setup-agent ";
            }
            if (!SSHCmdHelper.sshExecuteCmd(sshConnection, setupAgentCommand + parameters)) {
                String errorMsg = String.format("CloudStack Agent setup through command [%s] with parameters [%s] failed.",
                        setupAgentCommand, parameters);
                logger.info(errorMsg);
                throw new DiscoveredWithErrorException(errorMsg);
            }

            KvmDummyResourceBase kvmResource = new KvmDummyResourceBase();
            Map<String, Object> params = new HashMap<String, Object>();

            params.put("router.aggregation.command.each.timeout", _configDao.getValue(Config.RouterAggregationCommandEachTimeout.toString()));

            params.put("zone", Long.toString(dcId));
            params.put("pod", Long.toString(podId));
            params.put("cluster", Long.toString(clusterId));
            params.put("guid", guid);
            params.put("agentIp", agentIp);
            kvmResource.configure("kvm agent", params);
            resources.put(kvmResource, details);

            HostVO connectedHost = waitForHostConnect(dcId, podId, clusterId, guid);
            if (connectedHost == null)
                return null;

            details.put("guid", connectedHost.getGuid());

            // save user name and password
            _hostDao.loadDetails(connectedHost);
            Map<String, String> hostDetails = connectedHost.getDetails();
            hostDetails.put("password", password);
            hostDetails.put("username", username);
            _hostDao.saveDetails(connectedHost);
            return resources;
        } catch (DiscoveredWithErrorException e) {
            logger.error("DiscoveredWithErrorException caught and rethrowing, message: "+ e.getMessage());
            throw e;
        } catch (Exception e) {
            String msg = " can't setup agent, due to " + e.toString() + " - " + e.getMessage();
            logger.warn(msg);
            if (logger.isDebugEnabled()) {
                logger.debug(msg, e);
            }
            throw new DiscoveredWithErrorException(msg, e);
        } finally {
            if (sshConnection != null)
                sshConnection.close();
        }
    }

    private HostVO waitForHostConnect(long dcId, long podId, long clusterId, String guid) {
        for (int i = 0; i < _waitTime * 2; i++) {
            List<HostVO> hosts = _resourceMgr.listAllUpAndEnabledHosts(Host.Type.Routing, clusterId, podId, dcId);
            for (HostVO host : hosts) {
                if (host.getGuid().toLowerCase().startsWith(guid.toLowerCase())) {
                    return host;
                }
            }
            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                logger.debug("Failed to sleep: " + e.toString());
            }
        }
        logger.debug("Timeout, to wait for the host connecting to mgt svr, assuming it is failed");
        List<HostVO> hosts = _resourceMgr.findHostByGuid(dcId, guid);
        if (hosts.size() == 1) {
            return hosts.get(0);
        } else {
            return null;
        }
    }

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        // _setupAgentPath = Script.findScript(getPatchPath(),
        // "setup_agent.sh");
        _kvmPrivateNic = _configDao.getValue(Config.KvmPrivateNetwork.key());
        if (_kvmPrivateNic == null) {
            _kvmPrivateNic = "cloudbr0";
        }

        _kvmPublicNic = _configDao.getValue(Config.KvmPublicNetwork.key());
        if (_kvmPublicNic == null) {
            _kvmPublicNic = _kvmPrivateNic;
        }

        _kvmGuestNic = _configDao.getValue(Config.KvmGuestNetwork.key());
        if (_kvmGuestNic == null) {
            _kvmGuestNic = _kvmPrivateNic;
        }

        clusterExistingHostCache = new LazyCache<>(32, 30,
                this::getExistingHostForCluster);

        agentMgr.registerForHostEvents(this, true, false, false);
        _resourceMgr.registerResourceStateAdapter(this.getClass().getSimpleName(), this);
        return true;
    }

    protected String getPatchPath() {
        return "scripts/vm/hypervisor/kvm/";
    }

    @Override
    public void postDiscovery(List<HostVO> hosts, long msId) throws DiscoveryException {
        // TODO Auto-generated method stub
    }

    @Override
    public boolean matchHypervisor(String hypervisor) {
        // for backwards compatibility, if not supplied, always let to try it
        if (hypervisor == null)
            return true;

        return getHypervisorType().toString().equalsIgnoreCase(hypervisor);
    }

    @Override
    public HostVO createHostVOForConnectedAgent(HostVO host, StartupCommand[] cmd) {
        StartupCommand firstCmd = cmd[0];
        if (!(firstCmd instanceof StartupRoutingCommand)) {
            return null;
        }

        StartupRoutingCommand ssCmd = ((StartupRoutingCommand)firstCmd);
        if (ssCmd.getHypervisorType() != getHypervisorType()) {
            return null;
        }

        /* KVM requires host are the same in cluster */
        ClusterVO clusterVO = _clusterDao.findById(host.getClusterId());
        if (clusterVO == null) {
            logger.debug("cannot find cluster: " + host.getClusterId());
            throw new IllegalArgumentException("cannot add host, due to can't find cluster: " + host.getClusterId());
        }

        HostVO existingHostInCluster = clusterExistingHostCache.get(clusterVO.getId());
        if (existingHostInCluster != null) {
            String hostOsInCluster = existingHostInCluster.getDetail(HostInfo.HOST_OS);
            String hostOs = ssCmd.getHostDetails().get(HostInfo.HOST_OS);
            if (!isHostOsCompatibleWithOtherHost(hostOsInCluster, hostOs)) {
                String msg = String.format("host: %s with hostOS, \"%s\"into a cluster, in which there are \"%s\" hosts added", firstCmd.getPrivateIpAddress(), hostOs, hostOsInCluster);
                if (hostOs != null && hostOs.startsWith(hostOsInCluster)) {
                    logger.warn(String.format("Adding %s. This may or may not be ok!", msg));
                } else {
                    throw new IllegalArgumentException(String.format("Can't add %s.", msg));
                }
            }
        }

        _hostDao.loadDetails(host);

        return _resourceMgr.fillRoutingHostVO(host, ssCmd, getHypervisorType(), host.getDetails(), null);
    }

    protected boolean isHostOsCompatibleWithOtherHost(String hostOsInCluster, String hostOs) {
        if (hostOsInCluster.equalsIgnoreCase(hostOs)) {
            return true;
        }
        if (COMPATIBLE_HOST_OSES.contains(hostOsInCluster) && COMPATIBLE_HOST_OSES.contains(hostOs)) {
            logger.info(String.format("The host OS (%s) is compatible with the existing host OS (%s) in the cluster.", hostOs, hostOsInCluster));
            return true;
        }
        return false;
    }

    @Override
    public HostVO createHostVOForDirectConnectAgent(HostVO host, StartupCommand[] startup, ServerResource resource, Map<String, String> details, List<String> hostTags) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public DeleteHostAnswer deleteHost(HostVO host, boolean isForced, boolean isForceDeleteStorage) throws UnableDeleteHostException {
        if (host.getType() != Host.Type.Routing || (host.getHypervisorType() != HypervisorType.KVM && host.getHypervisorType() != HypervisorType.LXC)) {
            return null;
        }

        _resourceMgr.deleteRoutingHost(host, isForced, isForceDeleteStorage);
        try {
            ShutdownCommand cmd = new ShutdownCommand(ShutdownCommand.DeleteHost, null, !ADD_HOST_ON_SERVICE_RESTART_KVM.value());
            agentMgr.send(host.getId(), cmd);
        } catch (AgentUnavailableException e) {
            logger.warn("Sending ShutdownCommand failed: ", e);
        } catch (OperationTimedoutException e) {
            logger.warn("Sending ShutdownCommand failed: ", e);
        }

        return new DeleteHostAnswer(true);
    }

    @Override
    public boolean stop() {
        _resourceMgr.unregisterResourceStateAdapter(this.getClass().getSimpleName());
        return super.stop();
    }
}
