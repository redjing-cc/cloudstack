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
package com.cloud.cluster;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.rmi.RemoteException;
import java.sql.Connection;
import java.sql.SQLNonTransientException;
import java.sql.SQLRecoverableException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.command.ReconcileCommandService;
import org.apache.cloudstack.framework.config.ConfigDepot;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.management.ManagementServerHost;
import org.apache.cloudstack.utils.identity.ManagementServerNode;

import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.cluster.dao.ManagementServerHostPeerDao;
import com.cloud.cluster.dao.ManagementServerStatusDao;
import com.cloud.utils.DateUtil;
import com.cloud.utils.Profiler;
import com.cloud.utils.component.ComponentLifecycle;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.ConnectionConcierge;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.DbProperties;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.events.SubscriptionMgr;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.exception.ExceptionUtil;
import com.cloud.utils.mgmt.JmxUtil;
import com.cloud.utils.net.NetUtils;

public class ClusterManagerImpl extends ManagerBase implements ClusterManager, Configurable {

    private static final int EXECUTOR_SHUTDOWN_TIMEOUT = 1000; // 1 second
    private static final int DEFAULT_OUTGOING_WORKERS = 5;

    private final List<ClusterManagerListener> _listeners = new ArrayList<>();
    private final Map<Long, ManagementServerHostVO> _activePeers = new HashMap<>();

    private final Map<String, ClusterService> _clusterPeers;

    @Inject
    protected ConfigDepot _configDepot;

    private final ScheduledExecutorService _heartbeatScheduler = Executors.newScheduledThreadPool(1, new NamedThreadFactory("Cluster-Heartbeat"));
    private final ExecutorService _notificationExecutor = Executors.newFixedThreadPool(1, new NamedThreadFactory("Cluster-Notification"));
    private final List<ClusterManagerMessage> _notificationMsgs = new ArrayList<>();
    private ConnectionConcierge _heartbeatConnection = null;

    private final ExecutorService _executor;

    private ClusterServiceAdapter _currentServiceAdapter;

    @Inject
    private List<ClusterServiceAdapter> _serviceAdapters;

    @Inject
    private ManagementServerHostDao _mshostDao;
    @Inject
    private ManagementServerStatusDao mshostStatusDao;
    @Inject
    private ManagementServerHostPeerDao _mshostPeerDao;

    protected Dispatcher _dispatcher;

    private StatusAdministrator statusAdministrator;

    @Inject
    protected ReconcileCommandService reconcileCommandService;

    //
    // pay attention to _mshostId and _msid
    // _mshostId is the primary key of management host table
    // _msid is the unique persistent identifier that peer name is based upon
    //
    private Long _mshostId = null;
    private ManagementServerHostVO _mshost = null;
    protected long _msId = ManagementServerNode.getManagementServerId();
    protected long _runId = System.currentTimeMillis();

    private boolean _peerScanInited = false;

    private String _clusterNodeIP = "127.0.0.1";

    private final List<ClusterServicePdu> _clusterPduOutgoingQueue = new ArrayList<>();
    private final List<ClusterServicePdu> _clusterPduIncomingQueue = new ArrayList<>();
    private final Map<Long, ClusterServiceRequestPdu> _outgoingPdusWaitingForAck = new HashMap<>();

    public ClusterManagerImpl() {
        _clusterPeers = new HashMap<>();

        // executor to perform remote-calls in another thread context, to avoid potential
        // recursive remote calls between nodes
        //
        _executor = Executors.newCachedThreadPool(new NamedThreadFactory("Cluster-Worker"));
        setRunLevel(ComponentLifecycle.RUN_LEVEL_COMPONENT);
    }

    private void registerRequestPdu(final ClusterServiceRequestPdu pdu) {
        synchronized (_outgoingPdusWaitingForAck) {
            _outgoingPdusWaitingForAck.put(pdu.getSequenceId(), pdu);
        }
    }

    @Override
    public void registerDispatcher(final Dispatcher dispatcher) {
        _dispatcher = dispatcher;
    }

    @Override
    public void registerStatusAdministrator(final StatusAdministrator administrator) {
        statusAdministrator = administrator;
    }

    private ClusterServiceRequestPdu popRequestPdu(final long ackSequenceId) {
        synchronized (_outgoingPdusWaitingForAck) {
            if (_outgoingPdusWaitingForAck.get(ackSequenceId) != null) {
                final ClusterServiceRequestPdu pdu = _outgoingPdusWaitingForAck.get(ackSequenceId);
                _outgoingPdusWaitingForAck.remove(ackSequenceId);
                return pdu;
            }
        }

        return null;
    }

    private void cancelClusterRequestToPeer(final String strPeer) {
        final List<ClusterServiceRequestPdu> candidates = new ArrayList<>();
        synchronized (_outgoingPdusWaitingForAck) {
            for (final Map.Entry<Long, ClusterServiceRequestPdu> entry : _outgoingPdusWaitingForAck.entrySet()) {
                if (entry.getValue().getDestPeer().equalsIgnoreCase(strPeer)) {
                    candidates.add(entry.getValue());
                }
            }

            for (final ClusterServiceRequestPdu pdu : candidates) {
                _outgoingPdusWaitingForAck.remove(pdu.getSequenceId());
            }
        }

        for (final ClusterServiceRequestPdu pdu : candidates) {
            logger.warn("Cancel cluster request PDU to peer: " + strPeer + ", pdu: " + pdu.getJsonPackage());
            synchronized (pdu) {
                pdu.notifyAll();
            }
        }
    }

    private void addOutgoingClusterPdu(final ClusterServicePdu pdu) {
        synchronized (_clusterPduOutgoingQueue) {
            _clusterPduOutgoingQueue.add(pdu);
            _clusterPduOutgoingQueue.notifyAll();
        }
    }

    private ClusterServicePdu popOutgoingClusterPdu(final long timeoutMs) {
        synchronized (_clusterPduOutgoingQueue) {
            try {
                _clusterPduOutgoingQueue.wait(timeoutMs);
            } catch (final InterruptedException ignored) {
            }

            if (_clusterPduOutgoingQueue.size() > 0) {
                final ClusterServicePdu pdu = _clusterPduOutgoingQueue.get(0);
                _clusterPduOutgoingQueue.remove(0);
                return pdu;
            }
        }
        return null;
    }

    private void addIncomingClusterPdu(final ClusterServicePdu pdu) {
        synchronized (_clusterPduIncomingQueue) {
            _clusterPduIncomingQueue.add(pdu);
            _clusterPduIncomingQueue.notifyAll();
        }
    }

    private ClusterServicePdu popIncomingClusterPdu(final long timeoutMs) {
        synchronized (_clusterPduIncomingQueue) {
            try {
                _clusterPduIncomingQueue.wait(timeoutMs);
            } catch (final InterruptedException ignored) {
            }

            if (_clusterPduIncomingQueue.size() > 0) {
                final ClusterServicePdu pdu = _clusterPduIncomingQueue.get(0);
                _clusterPduIncomingQueue.remove(0);
                return pdu;
            }
        }
        return null;
    }

    private Runnable getClusterPduSendingTask() {
        return new ManagedContextRunnable() {
            @Override
            protected void runInContext() {
                onSendingClusterPdu();
            }
        };
    }

    private Runnable getClusterPduNotificationTask() {
        return new ManagedContextRunnable() {
            @Override
            protected void runInContext() {
                onNotifyingClusterPdu();
            }
        };
    }

    private void onSendingClusterPdu() {
        while (true) {
            try {
                final ClusterServicePdu pdu = popOutgoingClusterPdu(1000);
                if (pdu == null) {
                    continue;
                }

                ClusterService peerService = null;
                for (int i = 0; i < 2; i++) {
                    try {
                        peerService = getPeerService(pdu.getDestPeer());
                    } catch (final RemoteException e) {
                        logger.error("Unable to get cluster service on peer : " + pdu.getDestPeer());
                    }

                    if (peerService != null) {
                        try {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Cluster PDU " + getSelfPeerName() + " -> " + pdu.getDestPeer() + ". agent: " + pdu.getAgentId() + ", pdu seq: " +
                                        pdu.getSequenceId() + ", pdu ack seq: " + pdu.getAckSequenceId() + ", json: " + pdu.getJsonPackage());
                            }

                            final Profiler profiler = new Profiler();
                            profiler.start();

                            final String strResult = peerService.execute(pdu);
                            profiler.stop();

                            if (logger.isDebugEnabled()) {
                                logger.debug("Cluster PDU " + getSelfPeerName() + " -> " + pdu.getDestPeer() + " completed. time: " +
                                        profiler.getDurationInMillis() + "ms. agent: " + pdu.getAgentId() + ", pdu seq: " + pdu.getSequenceId() +
                                        ", pdu ack seq: " + pdu.getAckSequenceId() + ", json: " + pdu.getJsonPackage());
                            }

                            if ("true".equals(strResult)) {
                                break;
                            }

                        } catch (final RemoteException e) {
                            invalidatePeerService(pdu.getDestPeer());
                            if (logger.isInfoEnabled()) {
                                logger.info("Exception on remote execution, peer: " + pdu.getDestPeer() + ", iteration: " + i + ", exception message :" +
                                        e.getMessage());
                            }
                        }
                    }
                }
            } catch (final Throwable e) {
                logger.error("Unexpected exception: ", e);
            }
        }
    }

    private void onNotifyingClusterPdu() {
        while (true) {
            try {
                final ClusterServicePdu pdu = popIncomingClusterPdu(1000);
                if (pdu == null) {
                    continue;
                }

                _executor.execute(new ManagedContextRunnable() {
                    @Override
                    protected void runInContext() {
                        if (pdu.getPduType() == ClusterServicePdu.PDU_TYPE_RESPONSE) {
                            final ClusterServiceRequestPdu requestPdu = popRequestPdu(pdu.getAckSequenceId());
                            if (requestPdu != null) {
                                requestPdu.setResponseResult(pdu.getJsonPackage());
                                synchronized (requestPdu) {
                                    requestPdu.notifyAll();
                                }
                            } else {
                                logger.warn("Original request has already been cancelled. pdu: " + pdu.getJsonPackage());
                            }
                        } else if (pdu.getPduType() == ClusterServicePdu.PDU_TYPE_STATUS_UPDATE) {
                            if (statusAdministrator == null) {
                                logger.warn("No status administration to report a status update too.");
                            } else {
                                statusAdministrator.newStatus(pdu);
                            }
                        } else {
                            String result = _dispatcher.dispatch(pdu);
                            if (result == null) {
                                result = "";
                            }

                            if (pdu.getPduType() == ClusterServicePdu.PDU_TYPE_REQUEST) {
                                final ClusterServicePdu responsePdu = new ClusterServicePdu();
                                responsePdu.setPduType(ClusterServicePdu.PDU_TYPE_RESPONSE);
                                responsePdu.setSourcePeer(pdu.getDestPeer());
                                responsePdu.setDestPeer(pdu.getSourcePeer());
                                responsePdu.setAckSequenceId(pdu.getSequenceId());
                                responsePdu.setJsonPackage(result);

                                addOutgoingClusterPdu(responsePdu);
                            }
                        }
                    }
                });
            } catch (final Throwable e) {
                logger.error("Unexpected exception: ", e);
            }
        }
    }

    @Override
    public void OnReceiveClusterServicePdu(final ClusterServicePdu pdu) {
        addIncomingClusterPdu(pdu);
    }

    /**
     * called by DatabaseUpgradeChecker to see if there are other peers running.
     *
     * @param notVersion
     *            If version is passed in, the peers CANNOT be running at this version. If version is null, return true if any
     *            peer is running regardless of version.
     * @return true if there are peers running and false if not.
     */
    public static final boolean arePeersRunning(final String notVersion) {
        return false; // TODO: Leaving this for Kelven to take care of.
    }

    @Override
    public void broadcast(final long agentId, final String cmds) {
        final Date cutTime = DateUtil.currentGMTTime();

        final List<ManagementServerHostVO> peers = _mshostDao.getActiveList(new Date(cutTime.getTime() - HeartbeatThreshold.value()));
        for (final ManagementServerHostVO peer : peers) {
            final String peerName = Long.toString(peer.getMsid());
            if (getSelfPeerName().equals(peerName)) {
                continue; // Skip myself.
            }
            try {
                if (logger.isDebugEnabled()) {
                    logger.debug("Forwarding {} to {}", cmds, peer);
                }
                executeAsync(peerName, agentId, cmds, true);
            } catch (final Exception e) {
                logger.warn("Caught exception while talking to {}", peer);
            }
        }
    }

    public void executeAsync(final String strPeer, final long agentId, final String cmds, final boolean stopOnError) {
        final ClusterServicePdu pdu = new ClusterServicePdu();
        pdu.setSourcePeer(getSelfPeerName());
        pdu.setDestPeer(strPeer);
        pdu.setAgentId(agentId);
        pdu.setJsonPackage(cmds);
        pdu.setStopOnError(true);
        addOutgoingClusterPdu(pdu);
    }

    @Override
    public void publishStatus(final String status) {
        final Date cutTime = DateUtil.currentGMTTime();

        final List<ManagementServerHostVO> peers = _mshostDao.getActiveList(new Date(cutTime.getTime() - HeartbeatThreshold.value()));
        for (final ManagementServerHostVO peer : peers) {
            final String peerName = Long.toString(peer.getMsid());
            try {
                if (logger.isDebugEnabled()) {
                    logger.debug("Forwarding {} to {}", status, peer);
                }
                sendStatus(peerName, status);
            } catch (final Exception e) {
                String msg = String.format("Caught exception while talking to %s", peer);
                logger.warn(msg);
                logger.debug(msg, e);
            }
        }
    }

    public void sendStatus(final String strPeer, final String status) {
        final ClusterServicePdu pdu = new ClusterServicePdu();
        pdu.setSourcePeer(getSelfPeerName());
        pdu.setDestPeer(strPeer);
        pdu.setPduType(ClusterServicePdu.PDU_TYPE_STATUS_UPDATE);
        pdu.setAgentId(0);
        pdu.setJsonPackage(status);
        pdu.setStopOnError(true);
        addOutgoingClusterPdu(pdu);
    }

    @Override
    public String execute(final String strPeer, final long agentId, final String cmds, final boolean stopOnError) {
        if (logger.isDebugEnabled()) {
            logger.debug(getSelfPeerName() + " -> " + strPeer + "." + agentId + " " + cmds);
        }

        final ClusterServiceRequestPdu pdu = new ClusterServiceRequestPdu();
        pdu.setSourcePeer(getSelfPeerName());
        pdu.setDestPeer(strPeer);
        pdu.setAgentId(agentId);
        pdu.setJsonPackage(cmds);
        pdu.setStopOnError(stopOnError);
        registerRequestPdu(pdu);
        addOutgoingClusterPdu(pdu);

        synchronized (pdu) {
            try {
                pdu.wait();
            } catch (final InterruptedException ignored) {
            }
        }

        if (logger.isDebugEnabled()) {
            logger.debug(getSelfPeerName() + " -> " + strPeer + "." + agentId + " completed. result: " + pdu.getResponseResult());
        }

        if (pdu.getResponseResult() != null && pdu.getResponseResult().length() > 0) {
            return pdu.getResponseResult();
        }

        return null;
    }

    @Override
    public ManagementServerHostVO getPeer(final String mgmtServerId) {
        return _mshostDao.findByMsid(Long.parseLong(mgmtServerId));
    }

    @Override
    public String getSelfPeerName() {
        return Long.toString(_msId);
    }

    @Override
    public String getSelfNodeIP() {
        return _clusterNodeIP;
    }

    @Override
    public void registerListener(final ClusterManagerListener listener) {
        // Note : we don't check duplicates
        synchronized (_listeners) {

            logger.info("register cluster listener " + listener.getClass());

            _listeners.add(listener);
        }
    }

    @Override
    public void unregisterListener(final ClusterManagerListener listener) {
        synchronized (_listeners) {
            logger.info("unregister cluster listener " + listener.getClass());

            _listeners.remove(listener);
        }
    }

    public void notifyNodeJoined(final List<ManagementServerHostVO> nodeList) {
        if (logger.isDebugEnabled()) {
            logger.debug("Notify management server node join to listeners.");

            for (final ManagementServerHostVO mshost : nodeList) {
                logger.debug("Joining node, IP: {}, ms: {}", mshost.getServiceIP(), mshost);
            }
        }

        synchronized (_listeners) {
            for (final ClusterManagerListener listener : _listeners) {
                listener.onManagementNodeJoined(nodeList, _mshostId);
            }
        }

        SubscriptionMgr.getInstance().notifySubscribers(ClusterManager.ALERT_SUBJECT, this, new ClusterNodeJoinEventArgs(_mshostId, nodeList));
    }

    public void notifyNodeLeft(final List<ManagementServerHostVO> nodeList) {
        if (logger.isDebugEnabled()) {
            logger.debug("Notify management server node left to listeners.");
        }

        for (final ManagementServerHostVO mshost : nodeList) {
            if (logger.isDebugEnabled()) {
                logger.debug("Leaving node, IP: {}, ms: {}", mshost.getServiceIP(), mshost);
            }
            cancelClusterRequestToPeer(String.valueOf(mshost.getMsid()));
        }

        synchronized (_listeners) {
            for (final ClusterManagerListener listener : _listeners) {
                listener.onManagementNodeLeft(nodeList, _mshostId);
            }
        }

        SubscriptionMgr.getInstance().notifySubscribers(ClusterManager.ALERT_SUBJECT, this, new ClusterNodeLeftEventArgs(_mshostId, nodeList));
    }

    public void notifyNodeIsolated() {
        if (logger.isDebugEnabled()) {
            logger.debug("Notify management server node isolation to listeners");
        }

        synchronized (_listeners) {
            for (final ClusterManagerListener listener : _listeners) {
                listener.onManagementNodeIsolated();
            }
        }
    }

    public ClusterService getPeerService(final String strPeer) throws RemoteException {
        synchronized (_clusterPeers) {
            if (_clusterPeers.containsKey(strPeer)) {
                return _clusterPeers.get(strPeer);
            }
        }

        final ClusterService service = _currentServiceAdapter.getPeerService(strPeer);

        if (service != null) {
            synchronized (_clusterPeers) {
                // re-check the peer map again to deal with the
                // race conditions
                if (!_clusterPeers.containsKey(strPeer)) {
                    _clusterPeers.put(strPeer, service);
                }
            }
        }

        return service;
    }

    public void invalidatePeerService(final String strPeer) {
        synchronized (_clusterPeers) {
            if (_clusterPeers.containsKey(strPeer)) {
                _clusterPeers.remove(strPeer);
            }
        }
    }

    private Runnable getHeartbeatTask() {
        return new ManagedContextRunnable() {
            @Override
            protected void runInContext() {
                final TransactionLegacy txn = TransactionLegacy.open("ClusterHeartbeat");
                try {
                    final Profiler profiler = new Profiler();
                    final Profiler profilerHeartbeatUpdate = new Profiler();
                    final Profiler profilerPeerScan = new Profiler();

                    try {
                        profiler.start();

                        profilerHeartbeatUpdate.start();
                        txn.transitToAutoManagedConnection(TransactionLegacy.CLOUD_DB);
                        if (logger.isTraceEnabled()) {
                            logger.trace("Cluster manager heartbeat update, id: {}, mshost: {}", _mshostId, _mshost);
                        }

                        _mshostDao.update(_mshostId, _runId, DateUtil.currentGMTTime());
                        profilerHeartbeatUpdate.stop();

                        profilerPeerScan.start();
                        if (logger.isTraceEnabled()) {
                            logger.trace("Cluster manager peer-scan, id: {}, mshost: {}", _mshostId, _mshost);
                        }

                        if (!_peerScanInited) {
                            _peerScanInited = true;
                            initPeerScan();
                        }

                        peerScan();
                        profilerPeerScan.stop();

                    } finally {
                        profiler.stop();

                        if (profiler.getDurationInMillis() >= HeartbeatInterval.value()) {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Management server heartbeat takes too long to finish. profiler: " + profiler + ", profilerHeartbeatUpdate: " +
                                        profilerHeartbeatUpdate + ", profilerPeerScan: " + profilerPeerScan);
                            }
                        }
                    }

                } catch (final CloudRuntimeException e) {
                    logger.error("Runtime DB exception ", e.getCause());

                    if (e.getCause() instanceof ClusterInvalidSessionException) {
                        logger.error("Invalid cluster session found, fence it");
                        queueNotification(new ClusterManagerMessage(ClusterManagerMessage.MessageType.nodeIsolated));
                    }

                    if (isRootCauseConnectionRelated(e.getCause())) {
                        invalidHeartbeatConnection();
                    }
                } catch (final ActiveFencingException e) {
                    queueNotification(new ClusterManagerMessage(ClusterManagerMessage.MessageType.nodeIsolated));
                } catch (final Throwable e) {
                    logger.error("Unexpected exception in cluster heartbeat", e);
                    if (isRootCauseConnectionRelated(e.getCause())) {
                        invalidHeartbeatConnection();
                    }
                } finally {
                    txn.close("ClusterHeartbeat");
                }
            }
        };
    }

    private boolean isRootCauseConnectionRelated(Throwable e) {
        while (e != null) {
            if (e instanceof SQLRecoverableException || e instanceof SQLNonTransientException) {
                return true;
            }

            e = e.getCause();
        }

        return false;
    }

    private void invalidHeartbeatConnection() {
        if (_heartbeatConnection != null) {
            final Connection conn = TransactionLegacy.getStandaloneConnection();
            if (conn != null) {
                _heartbeatConnection.reset(conn);
            } else {
                logger.error("DB communication problem detected, fence it");
                queueNotification(new ClusterManagerMessage(ClusterManagerMessage.MessageType.nodeIsolated));
            }
            // The stand-alone connection does not have to be closed here because there will be another reference to it.
            // As a matter of fact, it will be assigned to the connection instance variable in the ConnectionConcierge class.
        }
    }

    private Runnable getNotificationTask() {
        return new ManagedContextRunnable() {
            @Override
            protected void runInContext() {
                while (true) {
                    synchronized (_notificationMsgs) {
                        try {
                            _notificationMsgs.wait(1000);
                        } catch (final InterruptedException ignored) {
                        }
                    }

                    ClusterManagerMessage msg = null;
                    while ((msg = getNextNotificationMessage()) != null) {
                        try {
                            switch (msg.getMessageType()) {
                            case nodeAdded:
                                if (msg.getNodes() != null && msg.getNodes().size() > 0) {
                                    final Profiler profiler = new Profiler();
                                    profiler.start();

                                    notifyNodeJoined(msg.getNodes());

                                    profiler.stop();
                                    if (profiler.getDurationInMillis() > 1000) {
                                        if (logger.isDebugEnabled()) {
                                            logger.debug("Notifying management server join event took " + profiler.getDurationInMillis() + " ms");
                                        }
                                    } else {
                                        logger.warn("Notifying management server join event took " + profiler.getDurationInMillis() + " ms");
                                    }
                                }
                                break;

                            case nodeRemoved:
                                if (msg.getNodes() != null && msg.getNodes().size() > 0) {
                                    final Profiler profiler = new Profiler();
                                    profiler.start();

                                    notifyNodeLeft(msg.getNodes());

                                    profiler.stop();
                                    if (profiler.getDurationInMillis() > 1000) {
                                        if (logger.isDebugEnabled()) {
                                            logger.debug("Notifying management server leave event took " + profiler.getDurationInMillis() + " ms");
                                        }
                                    } else {
                                        logger.warn("Notifying management server leave event took " + profiler.getDurationInMillis() + " ms");
                                    }
                                }
                                break;

                            case nodeIsolated:
                                notifyNodeIsolated();
                                break;

                            default:
                                assert false;
                                break;
                            }

                        } catch (final Throwable e) {
                            logger.warn("Unexpected exception during cluster notification. ", e);
                        }
                    }

                    try {
                        Thread.sleep(1000);
                    } catch (final InterruptedException ignored) {
                    }
                }
            }
        };
    }

    private void queueNotification(final ClusterManagerMessage msg) {
        synchronized (_notificationMsgs) {
            _notificationMsgs.add(msg);
            _notificationMsgs.notifyAll();
        }

        switch (msg.getMessageType()) {
        case nodeAdded:
        break;

        case nodeRemoved: {
            final List<ManagementServerHostVO> l = msg.getNodes();
            if (l != null && l.size() > 0) {
                for (final ManagementServerHostVO mshost : l) {
                    if (mshost.getId() != _mshostId) {
                        _mshostPeerDao.updatePeerInfo(_mshostId, mshost.getId(), mshost.getRunid(), ManagementServerHost.State.Down);
                    }
                }
            }
        }
        break;

        default:
            break;
        }
    }

    private ClusterManagerMessage getNextNotificationMessage() {
        synchronized (_notificationMsgs) {
            if (_notificationMsgs.size() > 0) {
                return _notificationMsgs.remove(0);
            }
        }

        return null;
    }

    private void initPeerScan() {
        // upon startup, for all inactive management server nodes that we see at startup time, we will send notification also to help upper layer perform
        // missed cleanup
        final Date cutTime = DateUtil.currentGMTTime();
        final List<ManagementServerHostVO> inactiveList = _mshostDao.getInactiveList(new Date(cutTime.getTime() - HeartbeatThreshold.value()));

        // We don't have foreign key constraints to enforce the mgmt_server_id integrity in host table, when user manually
        // remove records from mshost table, this will leave orphan mgmt_serve_id reference in host table.
        final List<Long> orphanList = _mshostDao.listOrphanMsids();
        if (orphanList.size() > 0) {
            for (final Long orphanMsid : orphanList) {
                // construct fake ManagementServerHostVO based on orphan MSID
                logger.info("Add orphan management server msid found in host table to initial clustering notification, orphan msid: " + orphanMsid);
                inactiveList.add(new ManagementServerHostVO(orphanMsid, 0, "orphan", 0, new Date()));
            }
        } else {
            logger.info("We are good, no orphan management server msid in host table is found");
        }

        if (inactiveList.size() > 0) {
            if (logger.isInfoEnabled()) {
                logger.info("Found " + inactiveList.size() + " inactive management server node based on timestamp");
                for (final ManagementServerHostVO host : inactiveList) {
                    logger.info("management server node ms: {}, service ip: {}, version: {}", host, host.getServiceIP(), host.getVersion());
                }
            }

            final List<ManagementServerHostVO> downHostList = new ArrayList<>();
            for (final ManagementServerHostVO host : inactiveList) {
                // Check if peer state is Up in the period
                if (!_mshostPeerDao.isPeerUpState(_mshostId, host.getId(), new Date(cutTime.getTime() - HeartbeatThreshold.value()))) {
                    logger.warn("Management node {} is detected inactive by timestamp and did not send node status to this node", host);
                    downHostList.add(host);
                }
            }

            if (downHostList.size() > 0) {
                queueNotification(new ClusterManagerMessage(ClusterManagerMessage.MessageType.nodeRemoved, downHostList));
            }
        } else {
            logger.info("No inactive management server node found");
        }
    }

    private void peerScan() throws ActiveFencingException {
        final Date cutTime = DateUtil.currentGMTTime();

        final Profiler profiler = new Profiler();
        profiler.start();

        final Profiler profilerQueryActiveList = new Profiler();
        profilerQueryActiveList.start();
        final List<ManagementServerHostVO> currentList = _mshostDao.getActiveList(new Date(cutTime.getTime() - HeartbeatThreshold.value()));
        profilerQueryActiveList.stop();

        final Profiler profilerSyncClusterInfo = new Profiler();
        profilerSyncClusterInfo.start();
        final List<ManagementServerHostVO> removedNodeList = new ArrayList<>();
        final List<ManagementServerHostVO> invalidatedNodeList = new ArrayList<>();

        if (_mshostId != null) {

            if (_mshostPeerDao.countStateSeenInPeers(_mshostId, _runId, ManagementServerHost.State.Down) > 0) {
                final String msg =
                        "We have detected that at least one management server peer reports that this management server is down, perform active fencing to avoid split-brain situation";
                logger.error(msg);
                throw new ActiveFencingException(msg);
            }

            // only if we have already attached to cluster, will we start to check leaving nodes
            for (final Map.Entry<Long, ManagementServerHostVO> entry : _activePeers.entrySet()) {

                final ManagementServerHostVO current = getInListById(entry.getKey(), currentList);
                if (current == null) {
                    if (entry.getKey().longValue() != _mshostId.longValue()) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Detected management node left {}, nodeIP:{}",
                                    entry.getValue(), entry.getValue().getServiceIP());
                        }
                        removedNodeList.add(entry.getValue());
                    }
                } else {
                    if (current.getRunid() == 0) {
                        if (entry.getKey().longValue() != _mshostId.longValue()) {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Detected management node left because of invalidated session {}, nodeIP:{}",
                                        entry.getValue(), entry.getValue().getServiceIP());
                            }
                            invalidatedNodeList.add(entry.getValue());
                        }
                    } else {
                        if (entry.getValue().getRunid() != current.getRunid()) {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Detected management node left and rejoined quickly {}, nodeIP:{}",
                                        entry.getValue(), entry.getValue().getServiceIP());
                            }

                            entry.getValue().setRunid(current.getRunid());
                        }
                    }
                }
            }
        }
        profilerSyncClusterInfo.stop();

        final Profiler profilerInvalidatedNodeList = new Profiler();
        profilerInvalidatedNodeList.start();
        processInvalidatedNodes(invalidatedNodeList);
        profilerInvalidatedNodeList.stop();

        final Profiler profilerRemovedList = new Profiler();
        profilerRemovedList.start();
        processRemovedNodes(cutTime, removedNodeList);
        profilerRemovedList.stop();

        final Profiler profilerNewList = new Profiler();
        profilerNewList.start();
        processNewNodes(cutTime, currentList);
        profilerNewList.stop();

        final Profiler profilerInactiveList = new Profiler();
        profilerInactiveList.start();
        processInactiveNodes(cutTime);
        profilerInactiveList.stop();

        profiler.stop();

        logger.debug(String.format("Peer scan is finished. profiler: %s , profilerQueryActiveList: %s, " +
                        ", profilerSyncClusterInfo: %s, profilerInvalidatedNodeList: %s, profilerRemovedList: %s," +
                        ", profilerNewList: %s, profilerInactiveList: %s",
                profiler, profilerQueryActiveList, profilerSyncClusterInfo, profilerInvalidatedNodeList, profilerRemovedList,
                profilerNewList, profilerInactiveList));

        if (profiler.getDurationInMillis() >= HeartbeatInterval.value()) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Peer scan takes too long to finish. profiler: %s , profilerQueryActiveList: %s, " +
                        ", profilerSyncClusterInfo: %s, profilerInvalidatedNodeList: %s, profilerRemovedList: %s," +
                        ", profilerNewList: %s, profilerInactiveList: %s",
                        profiler, profilerQueryActiveList, profilerSyncClusterInfo, profilerInvalidatedNodeList, profilerRemovedList,
                        profilerNewList, profilerInactiveList));
            }
        }
    }

    private void processInvalidatedNodes(List<ManagementServerHostVO> invalidatedNodeList) {
        // process invalidated node list
        if (invalidatedNodeList.size() > 0) {
            for (final ManagementServerHostVO mshost : invalidatedNodeList) {
                _activePeers.remove(mshost.getId());
                try {
                    JmxUtil.unregisterMBean("ClusterManager", "Node " + mshost.getId());
                } catch (final Exception e) {
                    logger.warn("Unable to deregister cluster node from JMX monitoring due to exception " + e);
                }
            }

            queueNotification(new ClusterManagerMessage(ClusterManagerMessage.MessageType.nodeRemoved, invalidatedNodeList));
        }
    }

    private void processRemovedNodes(Date cutTime, List<ManagementServerHostVO> removedNodeList) {
        // process removed node list
        final Iterator<ManagementServerHostVO> it = removedNodeList.iterator();
        while (it.hasNext()) {
            final ManagementServerHostVO mshost = it.next();
            // Check if peer state is Up in the period
            if (!_mshostPeerDao.isPeerUpState(_mshostId, mshost.getId(), new Date(cutTime.getTime() - HeartbeatThreshold.value()))) {
                logger.warn("Management node {} is detected inactive by timestamp and did not send node status to this node", mshost);
                _activePeers.remove(mshost.getId());
                try {
                    JmxUtil.unregisterMBean("ClusterManager", "Node " + mshost.getId());
                } catch (final Exception e) {
                    logger.warn("Unable to deregiester cluster node from JMX monitoring due to exception " + e);
                }
            } else {
                logger.info("Management node {} is detected inactive by timestamp but sent node status to this node", mshost);
                it.remove();
            }
        }

        if (removedNodeList.size() > 0) {
            queueNotification(new ClusterManagerMessage(ClusterManagerMessage.MessageType.nodeRemoved, removedNodeList));
        }
    }

    private void processNewNodes(Date cutTime, List<ManagementServerHostVO> currentList) {
        final List<ManagementServerHostVO> newNodeList = new ArrayList<>();
        for (final ManagementServerHostVO mshost : currentList) {
            if (!_activePeers.containsKey(mshost.getId())) {
                _activePeers.put(mshost.getId(), mshost);

                if (logger.isDebugEnabled()) {
                    logger.debug("Detected management node joined, {}, nodeIP:{}", mshost, mshost.getServiceIP());
                }
                newNodeList.add(mshost);

                try {
                    JmxUtil.registerMBean("ClusterManager", "Node " + mshost.getId(), new ClusterManagerMBeanImpl(this, mshost));
                } catch (final Exception e) {
                    logger.warn("Unable to register cluster node into JMX monitoring due to exception " + ExceptionUtil.toString(e));
                }
            }
        }

        if (newNodeList.size() > 0) {
            queueNotification(new ClusterManagerMessage(ClusterManagerMessage.MessageType.nodeAdded, newNodeList));
        }
    }

    private void processInactiveNodes(Date cutTime) {
        final List<ManagementServerHostVO> inactiveList = _mshostDao.getInactiveList(new Date(cutTime.getTime() - HeartbeatThreshold.value()));
        if (inactiveList.size() > 0) {
            if (logger.isInfoEnabled()) {
                logger.info(String.format("Found %s inactive management server node based on timestamp", inactiveList.size()));
            }
            for (final ManagementServerHostVO host : inactiveList) {
                logger.info(String.format("management server node msid: %s, name: %s, service ip: %s, version: %s",
                        host.getMsid(),  host.getName(), host.getServiceIP(), host.getVersion()));
                // Check if any peer state is Up in the period
                if (ManagementServerHost.State.Up.equals(host.getState()) &&
                        !_mshostPeerDao.isPeerUpState(host.getId(), new Date(cutTime.getTime() - HeartbeatThreshold.value()))) {
                    logger.warn("Management node " + host.getId() + " is detected inactive by timestamp and did not send node status to all other nodes");
                    host.setState(ManagementServerHost.State.Down);
                    _mshostDao.update(host.getId(), host);

                    reconcileCommandService.updateReconcileCommandToInterruptedByManagementServerId(host.getMsid());
                }
            }
        } else {
            logger.info("No inactive management server node found");
        }
    }


    private static ManagementServerHostVO getInListById(final Long id, final List<ManagementServerHostVO> l) {
        for (final ManagementServerHostVO mshost : l) {
            if (mshost.getId() == id) {
                return mshost;
            }
        }
        return null;
    }

    @Override
    @DB
    public boolean start() {
        if (logger.isInfoEnabled()) {
            logger.info("Starting Cluster manager, msid: {}, mshost: {}", _msId, _mshost);
        }

        final ManagementServerHostVO mshost = Transaction.execute(new TransactionCallback<>() {
            @Override
            public ManagementServerHostVO doInTransaction(final TransactionStatus status) {

                final Class<?> c = this.getClass();
                final String version = c.getPackage().getImplementationVersion();

                ManagementServerHostVO mshost = _mshostDao.findByMsid(_msId);
                if (mshost == null) {
                    mshost = new ManagementServerHostVO();
                    mshost.setMsid(_msId);
                    mshost.setRunid(_runId);
                    mshost.setName(NetUtils.getCanonicalHostName());
                    mshost.setVersion(version);
                    mshost.setServiceIP(_clusterNodeIP);
                    mshost.setServicePort(_currentServiceAdapter.getServicePort());
                    mshost.setLastUpdateTime(DateUtil.currentGMTTime());
                    mshost.setRemoved(null);
                    mshost.setAlertCount(0);
                    mshost.setState(ManagementServerHost.State.Up);
                    mshost.setUuid(UUID.randomUUID().toString());
                    _mshostDao.persist(mshost);
                    if (logger.isInfoEnabled()) {
                        logger.info("New instance of management server {}, runId {} is being started", mshost, _runId);
                    }
                } else {
                    ManagementServerHost.State msHostState = ManagementServerHost.State.Up;
                    if (ManagementServerHost.State.Maintenance.equals(mshost.getState()) || ManagementServerHost.State.PreparingForMaintenance.equals(mshost.getState())) {
                        msHostState = ManagementServerHost.State.Maintenance;
                    }
                    _mshostDao.update(mshost.getId(), _runId, NetUtils.getCanonicalHostName(), version, _clusterNodeIP, _currentServiceAdapter.getServicePort(),
                            DateUtil.currentGMTTime(), msHostState);
                    if (logger.isInfoEnabled()) {
                        logger.info("Management server {}, runId {} is being started", mshost, _runId);
                    }
                }

                return mshost;
            }
        });

        _mshost = mshost;
        _mshostId = mshost.getId();
        if (logger.isInfoEnabled()) {
            logger.info("Management server (host : {}) is being started at {}:{}", _mshost, _clusterNodeIP, _currentServiceAdapter.getServicePort());
        }

        _mshostPeerDao.clearPeerInfo(_mshostId);

        // use separate thread for heartbeat updates
        _heartbeatScheduler.scheduleAtFixedRate(getHeartbeatTask(), HeartbeatInterval.value(), HeartbeatInterval.value(), TimeUnit.MILLISECONDS);
        _notificationExecutor.submit(getNotificationTask());

        if (logger.isInfoEnabled()) {
            logger.info("Cluster manager was started successfully");
        }

        return true;
    }

    @Override
    @DB
    public boolean stop() {
        if (logger.isInfoEnabled()) {
            logger.info("Stopping Cluster manager, msid : {}, runId : {}, host : {}", _msId, _runId, _mshost);
        }

        if (_mshostId != null) {
            ManagementServerHostVO mshost = _mshostDao.findByMsid(_msId);
            if (mshost != null) {
                ManagementServerStatusVO mshostStatus = mshostStatusDao.findByMsId(mshost.getUuid());
                if (mshostStatus != null) {
                    mshost.setState(ManagementServerHost.State.Down);
                    mshostStatus.setLastJvmStop(new Date());
                    _mshostDao.update(_mshostId, mshost);
                    mshostStatusDao.update(mshostStatus.getId(), mshostStatus);
                } else {
                    logger.warn("Found a management server host [{}] without a status. This should never happen!", mshost);
                    mshostStatus = new ManagementServerStatusVO();
                    mshostStatus.setMsId(mshost.getUuid());
                    mshostStatus.setLastSystemBoot(new Date());
                    mshostStatus.setLastJvmStart(new Date());
                    mshostStatus.setUpdated(new Date());
                    mshostStatusDao.persist(mshostStatus);
                }

                ManagementServerHost.State msHostState = ManagementServerHost.State.Down;
                if (ManagementServerHost.State.Maintenance.equals(mshost.getState()) || ManagementServerHost.State.PreparingForMaintenance.equals(mshost.getState())) {
                    msHostState = ManagementServerHost.State.Maintenance;
                }
                _mshostDao.updateState(mshost.getId(), msHostState);
            }
        }

        _heartbeatScheduler.shutdownNow();
        _executor.shutdownNow();

        try {
            _heartbeatScheduler.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT, TimeUnit.MILLISECONDS);
            _executor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException ignored) {
        }

        if (logger.isInfoEnabled()) {
            logger.info("Cluster manager is stopped");
        }

        return true;
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        if (logger.isInfoEnabled()) {
            logger.info("Start configuring cluster manager : " + name);
        }

        final Properties dbProps = DbProperties.getDbProperties();
        _clusterNodeIP = dbProps.getProperty("cluster.node.IP");
        if (_clusterNodeIP == null) {
            _clusterNodeIP = "127.0.0.1";
        }
        _clusterNodeIP = _clusterNodeIP.trim();

        if (logger.isInfoEnabled()) {
            logger.info("Cluster node IP : " + _clusterNodeIP);
        }

        if (!NetUtils.isLocalAddress(_clusterNodeIP)) {
            throw new ConfigurationException("cluster node IP should be valid local address where the server is running, please check your configuration");
        }

        for (int i = 0; i < DEFAULT_OUTGOING_WORKERS; i++) {
            _executor.execute(getClusterPduSendingTask());
        }

        // notification task itself in turn works as a task dispatcher
        _executor.execute(getClusterPduNotificationTask());

        if (_serviceAdapters == null) {
            throw new ConfigurationException("Unable to get cluster service adapters");
        }
        _currentServiceAdapter = _serviceAdapters.get(0);

        if (_currentServiceAdapter == null) {
            throw new ConfigurationException("Unable to set current cluster service adapter");
        }

        checkConflicts();

        if (logger.isInfoEnabled()) {
            logger.info("Cluster manager is configured.");
        }
        return true;
    }

    @Override
    public long getManagementNodeId() {
        return _msId;
    }

    @Override
    public long getCurrentRunId() {
        return _runId;
    }

    @Override
    public long getManagementRunId(final long msId) {
        final ManagementServerHostVO mshost = _mshostDao.findByMsid(msId);
        if (mshost != null) {
            return mshost.getRunid();
        }
        return -1;
    }

    public boolean isManagementNodeAlive(final long msid) {
        final ManagementServerHostVO mshost = _mshostDao.findByMsid(msid);
        if (mshost != null) {
            if (mshost.getLastUpdateTime().getTime() >= DateUtil.currentGMTTime().getTime() - HeartbeatThreshold.value()) {
                return true;
            }
        }

        return false;
    }

    public boolean pingManagementNode(final long msid) {
        final ManagementServerHostVO mshost = _mshostDao.findByMsid(msid);
        if (mshost == null) {
            return false;
        }

        return pingManagementNode(mshost);
    }

    @Override
    public String getConfigComponentName() {
        return ClusterManager.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {HeartbeatInterval, HeartbeatThreshold};
    }

    private boolean pingManagementNode(final ManagementServerHostVO mshost) {

        final String targetIp = mshost.getServiceIP();
        if ("127.0.0.1".equals(targetIp) || "0.0.0.0".equals(targetIp)) {
            logger.info("ping management node cluster service can not be performed on self");
            return false;
        }

        int retry = 10;
        while (--retry > 0) {
            SocketChannel sch = null;
            try {
                logger.info("Trying to connect to " + targetIp);
                sch = SocketChannel.open();
                sch.configureBlocking(true);
                sch.socket().setSoTimeout(5000);

                final InetSocketAddress addr = new InetSocketAddress(targetIp, mshost.getServicePort());
                sch.connect(addr);
                return true;
            } catch (final IOException e) {
                if (e instanceof ConnectException) {
                    logger.error("Unable to ping management server at " + targetIp + ":" + mshost.getServicePort() + " due to ConnectException");
                    if (logger.isDebugEnabled()) {
                        logger.debug("Unable to ping management server at " + targetIp + ":" + mshost.getServicePort() + " due to ConnectException", e);
                    }
                    return false;
                }
            } finally {
                if (sch != null) {
                    try {
                        sch.close();
                    } catch (final IOException ignored) {
                    }
                }
            }

            try {
                Thread.sleep(1000);
            } catch (final InterruptedException ignored) {
            }
        }

        logger.error("Unable to ping management server at " + targetIp + ":" + mshost.getServicePort() + " after retries");
        return false;
    }

    public int getHeartbeatInterval() {
        return HeartbeatInterval.value();
    }

    private void checkConflicts() throws ConfigurationException {
        final Date cutTime = DateUtil.currentGMTTime();
        final List<ManagementServerHostVO> peers = _mshostDao.getActiveList(new Date(cutTime.getTime() - HeartbeatThreshold.value()));
        for (final ManagementServerHostVO peer : peers) {
            final String peerIP = peer.getServiceIP().trim();
            if (_clusterNodeIP.equals(peerIP)) {
                if ("127.0.0.1".equals(_clusterNodeIP)) {
                    if (pingManagementNode(peer.getMsid())) {
                        final String msg = "Detected another management node with localhost IP is already running, please check your cluster configuration";
                        logger.error(msg);
                        throw new ConfigurationException(msg);
                    } else {
                        final String msg =
                                "Detected another management node with localhost IP is considered as running in DB, however it is not pingable, we will continue cluster initialization with this management server node";
                        logger.info(msg);
                    }
                } else {
                    if (pingManagementNode(peer.getMsid())) {
                        final String msg =
                                "Detected that another management node with the same IP " + peer.getServiceIP() +
                                " is already running, please check your cluster configuration";
                        logger.error(msg);
                        throw new ConfigurationException(msg);
                    } else {
                        final String msg =
                                "Detected that another management node with the same IP " + peer.getServiceIP() +
                                " is considered as running in DB, however it is not pingable, we will continue cluster initialization with this management server node";
                        logger.info(msg);
                    }
                }
            }
        }
    }
}
