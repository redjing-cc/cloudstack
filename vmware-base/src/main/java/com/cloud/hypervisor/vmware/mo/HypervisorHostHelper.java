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
package com.cloud.hypervisor.vmware.mo;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.utils.security.ParserUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.traversal.DocumentTraversal;
import org.w3c.dom.traversal.NodeFilter;
import org.w3c.dom.traversal.NodeIterator;
import org.xml.sax.SAXException;

import com.cloud.exception.CloudException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.vmware.util.VmwareContext;
import com.cloud.hypervisor.vmware.util.VmwareHelper;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.offering.NetworkOffering;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.utils.ActionDelegate;
import com.cloud.utils.LogUtils;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.cisco.n1kv.vsm.NetconfHelper;
import com.cloud.utils.cisco.n1kv.vsm.PolicyMap;
import com.cloud.utils.cisco.n1kv.vsm.PortProfile;
import com.cloud.utils.cisco.n1kv.vsm.VsmCommand.BindingType;
import com.cloud.utils.cisco.n1kv.vsm.VsmCommand.OperationType;
import com.cloud.utils.cisco.n1kv.vsm.VsmCommand.PortProfileType;
import com.cloud.utils.cisco.n1kv.vsm.VsmCommand.SwitchPortMode;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.nicira.nvp.plugin.NiciraNvpApiVersion;
import com.vmware.vim25.AlreadyExistsFaultMsg;
import com.vmware.vim25.BoolPolicy;
import com.vmware.vim25.ClusterConfigInfoEx;
import com.vmware.vim25.ConcurrentAccessFaultMsg;
import com.vmware.vim25.CustomFieldStringValue;
import com.vmware.vim25.DVPortSetting;
import com.vmware.vim25.DVPortgroupConfigInfo;
import com.vmware.vim25.DVPortgroupConfigSpec;
import com.vmware.vim25.DVSMacLearningPolicy;
import com.vmware.vim25.DVSMacManagementPolicy;
import com.vmware.vim25.DVSSecurityPolicy;
import com.vmware.vim25.DVSTrafficShapingPolicy;
import com.vmware.vim25.DatacenterConfigInfo;
import com.vmware.vim25.DuplicateNameFaultMsg;
import com.vmware.vim25.DynamicProperty;
import com.vmware.vim25.FileFaultFaultMsg;
import com.vmware.vim25.HostNetworkSecurityPolicy;
import com.vmware.vim25.HostNetworkTrafficShapingPolicy;
import com.vmware.vim25.HostPortGroup;
import com.vmware.vim25.HostPortGroupSpec;
import com.vmware.vim25.HostVirtualSwitch;
import com.vmware.vim25.HttpNfcLeaseDeviceUrl;
import com.vmware.vim25.HttpNfcLeaseInfo;
import com.vmware.vim25.HttpNfcLeaseState;
import com.vmware.vim25.InsufficientResourcesFaultFaultMsg;
import com.vmware.vim25.InvalidDatastoreFaultMsg;
import com.vmware.vim25.InvalidNameFaultMsg;
import com.vmware.vim25.InvalidStateFaultMsg;
import com.vmware.vim25.LocalizedMethodFault;
import com.vmware.vim25.LongPolicy;
import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.MethodFault;
import com.vmware.vim25.NumericRange;
import com.vmware.vim25.ObjectContent;
import com.vmware.vim25.OptionValue;
import com.vmware.vim25.OutOfBoundsFaultMsg;
import com.vmware.vim25.OvfCreateDescriptorParams;
import com.vmware.vim25.OvfCreateDescriptorResult;
import com.vmware.vim25.OvfCreateImportSpecParams;
import com.vmware.vim25.OvfCreateImportSpecResult;
import com.vmware.vim25.OvfFile;
import com.vmware.vim25.OvfFileItem;
import com.vmware.vim25.ParaVirtualSCSIController;
import com.vmware.vim25.RuntimeFaultFaultMsg;
import com.vmware.vim25.TaskInProgressFaultMsg;
import com.vmware.vim25.VMwareDVSConfigSpec;
import com.vmware.vim25.VMwareDVSPortSetting;
import com.vmware.vim25.VMwareDVSPortgroupPolicy;
import com.vmware.vim25.VMwareDVSPvlanConfigSpec;
import com.vmware.vim25.VMwareDVSPvlanMapEntry;
import com.vmware.vim25.VirtualBusLogicController;
import com.vmware.vim25.VirtualController;
import com.vmware.vim25.VirtualDevice;
import com.vmware.vim25.VirtualDeviceConfigSpec;
import com.vmware.vim25.VirtualDeviceConfigSpecOperation;
import com.vmware.vim25.VirtualDisk;
import com.vmware.vim25.VirtualIDEController;
import com.vmware.vim25.VirtualLsiLogicController;
import com.vmware.vim25.VirtualLsiLogicSASController;
import com.vmware.vim25.VirtualMachineConfigSpec;
import com.vmware.vim25.VirtualMachineFileInfo;
import com.vmware.vim25.VirtualMachineGuestOsIdentifier;
import com.vmware.vim25.VirtualMachineImportSpec;
import com.vmware.vim25.VirtualMachineVideoCard;
import com.vmware.vim25.VirtualSCSIController;
import com.vmware.vim25.VirtualSCSISharing;
import com.vmware.vim25.VmConfigFaultFaultMsg;
import com.vmware.vim25.VmwareDistributedVirtualSwitchPvlanSpec;
import com.vmware.vim25.VmwareDistributedVirtualSwitchTrunkVlanSpec;
import com.vmware.vim25.VmwareDistributedVirtualSwitchVlanIdSpec;
import com.vmware.vim25.VmwareDistributedVirtualSwitchVlanSpec;

public class HypervisorHostHelper {
    protected static Logger LOGGER = LogManager.getLogger(HypervisorHostHelper.class);
    private static final int DEFAULT_LOCK_TIMEOUT_SECONDS = 600;
    private static final String s_policyNamePrefix = "cloud.policy.";

    // make vmware-base loosely coupled with cloud-specific stuff, duplicate VLAN.UNTAGGED constant here
    private static final String UNTAGGED_VLAN_NAME = "untagged";
    private static final String VMDK_PACK_DIR = "ova";
    private static final String OVA_OPTION_KEY_BOOTDISK = "cloud.ova.bootdisk";
    public static final String VSPHERE_DATASTORE_BASE_FOLDER = "fcd";
    public static final String VSPHERE_DATASTORE_HIDDEN_FOLDER = ".hidden";

    protected final static Map<String, Integer> apiVersionHardwareVersionMap;

    static {
        apiVersionHardwareVersionMap = new HashMap<String, Integer>();
        apiVersionHardwareVersionMap.put("3.5", 4);
        apiVersionHardwareVersionMap.put("3.6", 4);
        apiVersionHardwareVersionMap.put("3.7", 4);
        apiVersionHardwareVersionMap.put("3.8", 4);
        apiVersionHardwareVersionMap.put("3.9", 4);
        apiVersionHardwareVersionMap.put("4.0", 7);
        apiVersionHardwareVersionMap.put("4.1", 7);
        apiVersionHardwareVersionMap.put("4.2", 7);
        apiVersionHardwareVersionMap.put("4.3", 7);
        apiVersionHardwareVersionMap.put("4.4", 7);
        apiVersionHardwareVersionMap.put("4.5", 7);
        apiVersionHardwareVersionMap.put("4.6", 7);
        apiVersionHardwareVersionMap.put("4.7", 7);
        apiVersionHardwareVersionMap.put("4.8", 7);
        apiVersionHardwareVersionMap.put("4.9", 7);
        apiVersionHardwareVersionMap.put("5.0", 8);
        apiVersionHardwareVersionMap.put("5.1", 9);
        apiVersionHardwareVersionMap.put("5.2", 9);
        apiVersionHardwareVersionMap.put("5.3", 9);
        apiVersionHardwareVersionMap.put("5.4", 9);
        apiVersionHardwareVersionMap.put("5.5", 10);
        apiVersionHardwareVersionMap.put("5.6", 10);
        apiVersionHardwareVersionMap.put("5.7", 10);
        apiVersionHardwareVersionMap.put("5.8", 10);
        apiVersionHardwareVersionMap.put("5.9", 10);
        apiVersionHardwareVersionMap.put("6.0", 11);
        apiVersionHardwareVersionMap.put("6.1", 11);
        apiVersionHardwareVersionMap.put("6.2", 11);
        apiVersionHardwareVersionMap.put("6.3", 11);
        apiVersionHardwareVersionMap.put("6.4", 11);
        apiVersionHardwareVersionMap.put("6.5", 13);
        apiVersionHardwareVersionMap.put("6.6", 13);
        apiVersionHardwareVersionMap.put("6.7", 14);
        apiVersionHardwareVersionMap.put("6.8", 14);
        apiVersionHardwareVersionMap.put("6.9", 14);
        apiVersionHardwareVersionMap.put("7.0", 17);
    }
    private static final String MINIMUM_VCENTER_API_VERSION_WITH_DVS_NEW_POLICIES_SUPPORT = "6.7";
    private static final String MINIMUM_DVS_VERSION_WITH_NEW_POLICIES_SUPPORT = "6.6.0";

    private static boolean isVersionEqualOrHigher(String check, String base) {
        if (check == null || base == null) {
            return false;
        }
        ComparableVersion baseVersion = new ComparableVersion(base);
        ComparableVersion checkVersion = new ComparableVersion(check);
        return checkVersion.compareTo(baseVersion) >= 0;
    }

    public static VirtualMachineMO findVmFromObjectContent(VmwareContext context, ObjectContent[] ocs, String name, String instanceNameCustomField) {

        if (ocs != null && ocs.length > 0) {
            for (ObjectContent oc : ocs) {
                String vmNameInvCenter = null;
                String vmInternalCSName = null;
                List<DynamicProperty> objProps = oc.getPropSet();
                if (objProps != null) {
                    for (DynamicProperty objProp : objProps) {
                        if (objProp.getName().equals("name")) {
                            vmNameInvCenter = (String)objProp.getVal();
                        } else if (objProp.getName().contains(instanceNameCustomField)) {
                            if (objProp.getVal() != null)
                                vmInternalCSName = ((CustomFieldStringValue)objProp.getVal()).getValue();
                        }

                        if ((vmNameInvCenter != null && name.equalsIgnoreCase(vmNameInvCenter)) || (vmInternalCSName != null && name.equalsIgnoreCase(vmInternalCSName))) {
                            VirtualMachineMO vmMo = new VirtualMachineMO(context, oc.getObj());
                            return vmMo;
                        }
                    }
                }
            }
        }
        return null;
    }

    public static ManagedObjectReference findDatastoreWithBackwardsCompatibility(VmwareHypervisorHost hyperHost, String uuidName) throws Exception {
        ManagedObjectReference morDs = hyperHost.findDatastore(uuidName.replace("-", ""));
        if (morDs == null)
            morDs = hyperHost.findDatastore(uuidName);

        return morDs;
    }

    public static String getSecondaryDatastoreUUID(String storeUrl) {
        return UUID.nameUUIDFromBytes(storeUrl.getBytes()).toString();
    }

    public static DatastoreMO getHyperHostDatastoreMO(VmwareHypervisorHost hyperHost, String datastoreName) throws Exception {
        ObjectContent[] ocs = hyperHost.getDatastorePropertiesOnHyperHost(new String[] {"name"});
        if (ocs != null && ocs.length > 0) {
            for (ObjectContent oc : ocs) {
                List<DynamicProperty> objProps = oc.getPropSet();
                if (objProps != null) {
                    for (DynamicProperty objProp : objProps) {
                        if (objProp.getVal().toString().equals(datastoreName))
                            return new DatastoreMO(hyperHost.getContext(), oc.getObj());
                    }
                }
            }
        }
        return null;
    }

    public static String getPublicNetworkNamePrefix(String vlanId) {
        if (UNTAGGED_VLAN_NAME.equalsIgnoreCase(vlanId)) {
            return "cloud.public.untagged";
        } else {
            return "cloud.public." + vlanId + ".";
        }
    }

    public static String composeCloudNetworkName(String prefix, String vlanId, String svlanId, Integer networkRateMbps, String vSwitchName, VirtualSwitchType vSwitchType) {
        StringBuffer sb = new StringBuffer(prefix);
        if (vlanId == null || UNTAGGED_VLAN_NAME.equalsIgnoreCase(vlanId)) {
            sb.append(".untagged");
        } else {
            if (vSwitchType != VirtualSwitchType.StandardVirtualSwitch && StringUtils.containsAny(vlanId, ",-")) {
                vlanId = com.cloud.utils.StringUtils.numbersToRange(vlanId);
            }
            sb.append(".").append(vlanId);
            if (svlanId != null) {
                sb.append(".").append("s" + svlanId);
            }
        }

        if (networkRateMbps != null && networkRateMbps.intValue() > 0)
            sb.append(".").append(String.valueOf(networkRateMbps));
        else
            sb.append(".0");
        sb.append(".").append(VersioningContants.PORTGROUP_NAMING_VERSION);
        sb.append("-").append(vSwitchName);

        String networkName = sb.toString();
        if (networkName.length() > 80) {
            // the maximum limit for a vSwitch name is 80 chars, applies to both standard and distributed virtual switches.
            LOGGER.warn(String.format("The network name: %s for the vSwitch %s of type %s, exceeds 80 chars", networkName, vSwitchName, vSwitchType));
        }
        return networkName;
    }

    public static Map<String, String> getValidatedVsmCredentials(VmwareContext context) throws Exception {
        Map<String, String> vsmCredentials = context.getStockObject("vsmcredentials");
        String msg;
        if (vsmCredentials == null || vsmCredentials.size() != 3) {
            msg = "Failed to retrieve required credentials of Nexus VSM from database.";
            LOGGER.error(msg);
            throw new Exception(msg);
        }

        String vsmIp = vsmCredentials.containsKey("vsmip") ? vsmCredentials.get("vsmip") : null;
        String vsmUserName = vsmCredentials.containsKey("vsmusername") ? vsmCredentials.get("vsmusername") : null;
        String vsmPassword = vsmCredentials.containsKey("vsmpassword") ? vsmCredentials.get("vsmpassword") : null;
        if (vsmIp == null || vsmIp.isEmpty() || vsmUserName == null || vsmUserName.isEmpty() || vsmPassword == null || vsmPassword.isEmpty()) {
            msg = "Detected invalid credentials for Nexus 1000v.";
            LOGGER.error(msg);
            throw new Exception(msg);
        }
        return vsmCredentials;
    }

    public static void createPortProfile(VmwareContext context, String ethPortProfileName, String networkName, Integer vlanId, Integer networkRateMbps,
            long peakBandwidth, long burstSize, String gateway, boolean configureVServiceInNexus) throws Exception {
        Map<String, String> vsmCredentials = getValidatedVsmCredentials(context);
        String vsmIp = vsmCredentials.get("vsmip");
        String vsmUserName = vsmCredentials.get("vsmusername");
        String vsmPassword = vsmCredentials.get("vsmpassword");
        String msg;

        NetconfHelper netconfClient;
        try {
            LOGGER.info("Connecting to Nexus 1000v: " + vsmIp);
            netconfClient = new NetconfHelper(vsmIp, vsmUserName, vsmPassword);
            LOGGER.info("Successfully connected to Nexus 1000v : " + vsmIp);
        } catch (CloudRuntimeException e) {
            msg = "Failed to connect to Nexus 1000v " + vsmIp + " with credentials of user " + vsmUserName + ". Exception: " + e.toString();
            LOGGER.error(msg);
            throw new CloudRuntimeException(msg);
        }

        String policyName = s_policyNamePrefix;
        int averageBandwidth = 0;
        if (networkRateMbps != null) {
            averageBandwidth = networkRateMbps.intValue();
            policyName += averageBandwidth;
        }

        try {
            // TODO(sateesh): Change the type of peakBandwidth & burstRate in
            // PolicyMap to long.
            if (averageBandwidth > 0) {
                LOGGER.debug("Adding policy map " + policyName);
                netconfClient.addPolicyMap(policyName, averageBandwidth, (int)peakBandwidth, (int)burstSize);
            }
        } catch (CloudRuntimeException e) {
            msg =
                    "Failed to add policy map of " + policyName + " with parameters " + "committed rate = " + averageBandwidth + "peak bandwidth = " + peakBandwidth +
                    "burst size = " + burstSize + ". Exception: " + e.toString();
            LOGGER.error(msg);
            if (netconfClient != null) {
                netconfClient.disconnect();
                LOGGER.debug("Disconnected Nexus 1000v session.");
            }
            throw new CloudRuntimeException(msg);
        }

        List<Pair<OperationType, String>> params = new ArrayList<Pair<OperationType, String>>();
        if (vlanId != null) {
            // No need to update ethernet port profile for untagged vlans
            params.add(new Pair<OperationType, String>(OperationType.addvlanid, vlanId.toString()));
            try {
                LOGGER.info("Updating Ethernet port profile " + ethPortProfileName + " with VLAN " + vlanId);
                netconfClient.updatePortProfile(ethPortProfileName, SwitchPortMode.trunk, params);
                LOGGER.info("Added " + vlanId + " to Ethernet port profile " + ethPortProfileName);
            } catch (CloudRuntimeException e) {
                msg = "Failed to update Ethernet port profile " + ethPortProfileName + " with VLAN " + vlanId + ". Exception: " + e.toString();
                LOGGER.error(msg);
                if (netconfClient != null) {
                    netconfClient.disconnect();
                    LOGGER.debug("Disconnected Nexus 1000v session.");
                }
                throw new CloudRuntimeException(msg);
            }
        }

        try {
            if (vlanId == null) {
                LOGGER.info("Adding port profile configured over untagged VLAN.");
                netconfClient.addPortProfile(networkName, PortProfileType.vethernet, BindingType.portbindingstatic, SwitchPortMode.access, 0);
            } else {
                if (!configureVServiceInNexus) {
                    LOGGER.info("Adding port profile configured over VLAN : " + vlanId.toString());
                    netconfClient.addPortProfile(networkName, PortProfileType.vethernet, BindingType.portbindingstatic, SwitchPortMode.access, vlanId.intValue());
                } else {
                    String tenant = "vlan-" + vlanId.intValue();
                    String vdc = "root/" + tenant + "/VDC-" + tenant;
                    String esp = "ESP-" + tenant;
                    LOGGER.info("Adding vservice node in Nexus VSM for VLAN : " + vlanId.toString());
                    netconfClient.addVServiceNode(vlanId.toString(), gateway);
                    LOGGER.info("Adding port profile with vservice details configured over VLAN : " + vlanId.toString());
                    netconfClient.addPortProfile(networkName, PortProfileType.vethernet, BindingType.portbindingstatic, SwitchPortMode.access, vlanId.intValue(), vdc,
                            esp);
                }
            }
        } catch (CloudRuntimeException e) {
            msg = "Failed to add vEthernet port profile " + networkName + "." + ". Exception: " + e.toString();
            LOGGER.error(msg);
            if (netconfClient != null) {
                netconfClient.disconnect();
                LOGGER.debug("Disconnected Nexus 1000v session.");
            }
            throw new CloudRuntimeException(msg);
        }

        try {
            if (averageBandwidth > 0) {
                LOGGER.info("Associating policy map " + policyName + " with port profile " + networkName + ".");
                netconfClient.attachServicePolicy(policyName, networkName);
            }
        } catch (CloudRuntimeException e) {
            msg = "Failed to associate policy map " + policyName + " with port profile " + networkName + ". Exception: " + e.toString();
            LOGGER.error(msg);
            throw new CloudRuntimeException(msg);
        } finally {
            if (netconfClient != null) {
                netconfClient.disconnect();
                LOGGER.debug("Disconnected Nexus 1000v session.");
            }
        }
    }

    public static void updatePortProfile(VmwareContext context, String ethPortProfileName, String vethPortProfileName, Integer vlanId, Integer networkRateMbps,
            long peakBandwidth, long burstRate) throws Exception {
        NetconfHelper netconfClient = null;
        Map<String, String> vsmCredentials = getValidatedVsmCredentials(context);
        String vsmIp = vsmCredentials.get("vsmip");
        String vsmUserName = vsmCredentials.get("vsmusername");
        String vsmPassword = vsmCredentials.get("vsmpassword");

        String msg;
        try {
            netconfClient = new NetconfHelper(vsmIp, vsmUserName, vsmPassword);
        } catch (CloudRuntimeException e) {
            msg = "Failed to connect to Nexus 1000v " + vsmIp + " with credentials of user " + vsmUserName + ". Exception: " + e.toString();
            LOGGER.error(msg);
            throw new CloudRuntimeException(msg);
        }

        PortProfile portProfile = netconfClient.getPortProfileByName(vethPortProfileName);
        int averageBandwidth = 0;
        String policyName = s_policyNamePrefix;
        if (networkRateMbps != null) {
            averageBandwidth = networkRateMbps.intValue();
            policyName += averageBandwidth;
        }

        if (averageBandwidth > 0) {
            PolicyMap policyMap = netconfClient.getPolicyMapByName(portProfile.inputPolicyMap);
            if (policyMap.committedRate == averageBandwidth && policyMap.peakRate == peakBandwidth && policyMap.burstRate == burstRate) {
                LOGGER.debug("Detected that policy map is already applied to port profile " + vethPortProfileName);
                if (netconfClient != null) {
                    netconfClient.disconnect();
                    LOGGER.debug("Disconnected Nexus 1000v session.");
                }
                return;
            } else {
                try {
                    // TODO(sateesh): Change the type of peakBandwidth &
                    // burstRate in PolicyMap to long.
                    LOGGER.info("Adding policy map " + policyName);
                    netconfClient.addPolicyMap(policyName, averageBandwidth, (int)peakBandwidth, (int)burstRate);
                } catch (CloudRuntimeException e) {
                    msg =
                            "Failed to add policy map of " + policyName + " with parameters " + "committed rate = " + averageBandwidth + "peak bandwidth = " + peakBandwidth +
                            "burst size = " + burstRate + ". Exception: " + e.toString();
                    LOGGER.error(msg);
                    if (netconfClient != null) {
                        netconfClient.disconnect();
                        LOGGER.debug("Disconnected Nexus 1000v session.");
                    }
                    throw new CloudRuntimeException(msg);
                }

                try {
                    LOGGER.info("Associating policy map " + policyName + " with port profile " + vethPortProfileName + ".");
                    netconfClient.attachServicePolicy(policyName, vethPortProfileName);
                } catch (CloudRuntimeException e) {
                    msg = "Failed to associate policy map " + policyName + " with port profile " + vethPortProfileName + ". Exception: " + e.toString();
                    LOGGER.error(msg);
                    if (netconfClient != null) {
                        netconfClient.disconnect();
                        LOGGER.debug("Disconnected Nexus 1000v session.");
                    }
                    throw new CloudRuntimeException(msg);
                }
            }
        }

        if (vlanId == null) {
            LOGGER.info("Skipping update operation over ethernet port profile " + ethPortProfileName + " for untagged VLAN.");
            if (netconfClient != null) {
                netconfClient.disconnect();
                LOGGER.debug("Disconnected Nexus 1000v session.");
            }
            return;
        }

        String currentVlan = portProfile.vlan;
        String newVlan = Integer.toString(vlanId.intValue());
        if (currentVlan.equalsIgnoreCase(newVlan)) {
            if (netconfClient != null) {
                netconfClient.disconnect();
                LOGGER.debug("Disconnected Nexus 1000v session.");
            }
            return;
        }

        List<Pair<OperationType, String>> params = new ArrayList<Pair<OperationType, String>>();
        params.add(new Pair<OperationType, String>(OperationType.addvlanid, newVlan));
        try {
            LOGGER.info("Updating vEthernet port profile with VLAN " + vlanId.toString());
            netconfClient.updatePortProfile(ethPortProfileName, SwitchPortMode.trunk, params);
        } catch (CloudRuntimeException e) {
            msg = "Failed to update ethernet port profile " + ethPortProfileName + " with parameters " + params.toString() + ". Exception: " + e.toString();
            LOGGER.error(msg);
            if (netconfClient != null) {
                netconfClient.disconnect();
                LOGGER.debug("Disconnected Nexus 1000v session.");
            }
            throw new CloudRuntimeException(msg);
        }

        try {
            netconfClient.updatePortProfile(vethPortProfileName, SwitchPortMode.access, params);
        } catch (CloudRuntimeException e) {
            msg = "Failed to update vEthernet port profile " + vethPortProfileName + " with parameters " + params.toString() + ". Exception: " + e.toString();
            LOGGER.error(msg);
            if (netconfClient != null) {
                netconfClient.disconnect();
                LOGGER.debug("Disconnected Nexus 1000v session.");
            }
            throw new CloudRuntimeException(msg);
        }
    }

    /**
     * Prepares network (for non-standard virtual switch) for the VM NIC based on the parameters.
     * Can create a new portgroup or update an existing.
     * @return Pair of network's ManagedObjectReference and name
     * @throws Exception
     */

    public static Pair<ManagedObjectReference, String> prepareNetwork(String physicalNetwork, String namePrefix, HostMO hostMo, String vlanId, String secondaryvlanId,
                                                                      Integer networkRateMbps, Integer networkRateMulticastMbps, long timeOutMs, VirtualSwitchType vSwitchType, int numPorts, String gateway,
                                                                      boolean configureVServiceInNexus, BroadcastDomainType broadcastDomainType, Map<String, String> vsmCredentials,
                                                                      Map<NetworkOffering.Detail, String> details, String netName) throws Exception {
        ManagedObjectReference morNetwork = null;
        VmwareContext context = hostMo.getContext();
        ManagedObjectReference dcMor = hostMo.getHyperHostDatacenter();
        DatacenterMO dataCenterMo = new DatacenterMO(context, dcMor);
        DistributedVirtualSwitchMO dvSwitchMo = null;
        ManagedObjectReference morEthernetPortProfile = null;
        String ethPortProfileName = null;
        ManagedObjectReference morDvSwitch = null;
        String dvSwitchName = null;
        boolean bWaitPortGroupReady = false;
        boolean createGCTag = false;
        String vcApiVersion;
        String minVcApiVersionSupportingAutoExpand;
        boolean autoExpandSupported;
        String networkName;
        Integer vid = null;
        Integer spvlanid = null;  // secondary pvlan id

        /** This is the list of BroadcastDomainTypes we can actually
         * prepare networks for in this function.
         */
        BroadcastDomainType[] supportedBroadcastTypes =
                new BroadcastDomainType[] {BroadcastDomainType.Lswitch, BroadcastDomainType.LinkLocal, BroadcastDomainType.Native, BroadcastDomainType.Pvlan,
                BroadcastDomainType.Storage, BroadcastDomainType.UnDecided, BroadcastDomainType.Vlan, BroadcastDomainType.NSX};

        if (!Arrays.asList(supportedBroadcastTypes).contains(broadcastDomainType)) {
            throw new InvalidParameterValueException("BroadcastDomainType " + broadcastDomainType + " it not supported on a VMWare hypervisor at this time.");
        }

        if (broadcastDomainType == BroadcastDomainType.Lswitch) {
            if (vSwitchType == VirtualSwitchType.NexusDistributedVirtualSwitch) {
                throw new InvalidParameterValueException("Nexus Distributed Virtualswitch is not supported with BroadcastDomainType " + broadcastDomainType);
            }
            /**
             * Nicira NVP requires all vms to be connected to a single port-group.
             * A unique vlan needs to be set per port. This vlan is specific to
             * this implementation and has no reference to other vlans in CS
             */
            networkName = "br-int"; // FIXME Should be set via a configuration item in CS
            // No doubt about this, depending on vid=null to avoid lots of code below
            vid = null;
        } else {
            if (vlanId != null) {
                vlanId = vlanId.replace("vlan://", "");
            }
            networkName = composeCloudNetworkName(namePrefix, vlanId, secondaryvlanId, networkRateMbps, physicalNetwork, vSwitchType);

            if (vlanId != null && !UNTAGGED_VLAN_NAME.equalsIgnoreCase(vlanId) && !StringUtils.containsAny(vlanId, ",-")) {
                createGCTag = true;
                vid = Integer.parseInt(vlanId);
            }
            if (vlanId != null && StringUtils.containsAny(vlanId, ",-")) {
                createGCTag = true;
            }
            if (secondaryvlanId != null) {
                spvlanid = Integer.parseInt(secondaryvlanId);
            }
        }

        if (vSwitchType == VirtualSwitchType.VMwareDistributedVirtualSwitch) {
            vcApiVersion = getVcenterApiVersion(context);
            minVcApiVersionSupportingAutoExpand = "5.0";
            autoExpandSupported = isFeatureSupportedInVcenterApiVersion(vcApiVersion, minVcApiVersionSupportingAutoExpand);

            dvSwitchName = physicalNetwork;
            // TODO(sateesh): Remove this after ensuring proper default value for vSwitchName throughout traffic types
            // and switch types.
            if (dvSwitchName == null) {
                LOGGER.warn("Detected null dvSwitch. Defaulting to dvSwitch0");
                dvSwitchName = "dvSwitch0";
            }
            morDvSwitch = dataCenterMo.getDvSwitchMor(dvSwitchName);
            if (morDvSwitch == null) {
                String msg = "Unable to find distributed vSwitch " + dvSwitchName;
                LOGGER.error(msg);
                throw new Exception(msg);
            }
            dvSwitchMo = new DistributedVirtualSwitchMO(context, morDvSwitch);
            String dvSwitchVersion = dvSwitchMo.getDVSProductVersion(morDvSwitch);
            LOGGER.debug(String.format("Found distributed vSwitch: %s with product version: %s", dvSwitchName, dvSwitchVersion));

            if (broadcastDomainType == BroadcastDomainType.Lswitch) {
                if (!dataCenterMo.hasDvPortGroup(networkName)) {
                    throw new InvalidParameterValueException("NVP integration port-group " + networkName + " does not exist on the DVS " + dvSwitchName);
                }
                bWaitPortGroupReady = false;
            } else if (BroadcastDomainType.NSX == broadcastDomainType && Objects.nonNull(netName)){
                networkName = netName;
                bWaitPortGroupReady = false;
            } else {
                boolean dvSwitchSupportNewPolicies = (isFeatureSupportedInVcenterApiVersion(vcApiVersion, MINIMUM_VCENTER_API_VERSION_WITH_DVS_NEW_POLICIES_SUPPORT)
                        && isVersionEqualOrHigher(dvSwitchVersion, MINIMUM_DVS_VERSION_WITH_NEW_POLICIES_SUPPORT));
                DVSTrafficShapingPolicy shapingPolicy = getDVSShapingPolicy(networkRateMbps);
                DVSSecurityPolicy secPolicy = createDVSSecurityPolicy(details);
                DVSMacManagementPolicy macManagementPolicy = createDVSMacManagementPolicy(details);

                // First, if both vlan id and pvlan id are provided, we need to
                // reconfigure the DVSwitch to have a tuple <vlan id, pvlan id> of
                // type isolated.
                String pvlanType = MapUtils.isNotEmpty(details) ? details.get(NetworkOffering.Detail.pvlanType) : null;
                if (vid != null && spvlanid != null) {
                    setupPVlanPair(dvSwitchMo, morDvSwitch, vid, spvlanid, pvlanType);
                }

                VMwareDVSPortgroupPolicy portGroupPolicy = null;
                // Next, create the port group. For this, we need to create a VLAN spec.
                createPortGroup(physicalNetwork, networkName, vlanId, vid, spvlanid, dataCenterMo, shapingPolicy,
                        secPolicy, macManagementPolicy, portGroupPolicy, dvSwitchMo, numPorts, autoExpandSupported,
                        dvSwitchSupportNewPolicies);
                bWaitPortGroupReady = true;
            }
        } else if (vSwitchType == VirtualSwitchType.NexusDistributedVirtualSwitch) {

            ethPortProfileName = physicalNetwork;
            // TODO(sateesh): Remove this after ensuring proper default value for vSwitchName throughout traffic types
            // and switch types.
            if (ethPortProfileName == null) {
                LOGGER.warn("Detected null ethrenet port profile. Defaulting to epp0.");
                ethPortProfileName = "epp0";
            }
            morEthernetPortProfile = dataCenterMo.getDvPortGroupMor(ethPortProfileName);
            if (morEthernetPortProfile == null) {
                String msg = "Unable to find Ethernet port profile " + ethPortProfileName;
                LOGGER.error(msg);
                throw new Exception(msg);
            } else {
                LOGGER.info("Found Ethernet port profile " + ethPortProfileName);
            }
            long averageBandwidth = 0L;
            if (networkRateMbps != null && networkRateMbps.intValue() > 0) {
                averageBandwidth = networkRateMbps.intValue() * 1024L * 1024L;
            }
            // We chose 50% higher allocation than average bandwidth.
            // TODO(sateesh): Optionally let user specify the peak coefficient
            long peakBandwidth = (long)(averageBandwidth * 1.5);
            // TODO(sateesh): Optionally let user specify the burst coefficient
            long burstSize = 5 * averageBandwidth / 8;
            if (vsmCredentials != null) {
                LOGGER.info("Stocking credentials of Nexus VSM");
                context.registerStockObject("vsmcredentials", vsmCredentials);
            }

            if (!dataCenterMo.hasDvPortGroup(networkName)) {
                LOGGER.info("Port profile " + networkName + " not found.");
                createPortProfile(context, physicalNetwork, networkName, vid, networkRateMbps, peakBandwidth, burstSize, gateway, configureVServiceInNexus);
                bWaitPortGroupReady = true;
            } else {
                LOGGER.info("Port profile " + networkName + " found.");
                updatePortProfile(context, physicalNetwork, networkName, vid, networkRateMbps, peakBandwidth, burstSize);
            }
        }
        // Wait for dvPortGroup on vCenter
        if (bWaitPortGroupReady)
            morNetwork = waitForDvPortGroupReady(dataCenterMo, networkName, timeOutMs);
        else
            morNetwork = dataCenterMo.getDvPortGroupMor(networkName);
        if (morNetwork == null) {
            String msg = "Failed to create guest network " + networkName;
            LOGGER.error(msg);
            throw new Exception(msg);
        }

        if (createGCTag) {
            NetworkMO networkMo = new NetworkMO(hostMo.getContext(), morNetwork);
            networkMo.setCustomFieldValue(CustomFieldConstants.CLOUD_GC_DVP, "true");
            LOGGER.debug("Added custom field : " + CustomFieldConstants.CLOUD_GC_DVP);
        }

        return new Pair<ManagedObjectReference, String>(morNetwork, networkName);
    }

    public static String getVcenterApiVersion(VmwareContext serviceContext) throws Exception {
        String vcApiVersion = null;
        if (serviceContext != null) {
            vcApiVersion = serviceContext.getServiceContent().getAbout().getApiVersion();
        }
        return vcApiVersion;
    }

    public static boolean isFeatureSupportedInVcenterApiVersion(String vCenterApiVersion, String minVcenterApiVersionForFeature) {
        return isVersionEqualOrHigher(vCenterApiVersion, minVcenterApiVersionForFeature);
    }

    private static void setupPVlanPair(DistributedVirtualSwitchMO dvSwitchMo, ManagedObjectReference morDvSwitch, Integer vid, Integer spvlanid, String pvlanType) throws Exception {
        LOGGER.debug(String.format("Setting up PVLAN on dvSwitch %s with the following information: %s %s %s", dvSwitchMo.getName(), vid, spvlanid, pvlanType));
        Map<Integer, HypervisorHostHelper.PvlanType> vlanmap = dvSwitchMo.retrieveVlanPvlan(vid, spvlanid, morDvSwitch);
        if (!vlanmap.isEmpty()) {
            // Then either vid or pvlanid or both are already being used. Check how.
            // First the primary pvlan id.
            if (vlanmap.containsKey(vid) && !vlanmap.get(vid).equals(HypervisorHostHelper.PvlanType.promiscuous)) {
                // This VLAN ID is already setup as a non-promiscuous vlan id on the DVS. Throw an exception.
                String msg = "Specified primary PVLAN ID " + vid + " is already in use as a " + vlanmap.get(vid).toString() + " VLAN on the DVSwitch";
                LOGGER.error(msg);
                throw new Exception(msg);
            }
            // Next the secondary pvlan id.
            if (spvlanid.equals(vid)) {
                if (vlanmap.containsKey(spvlanid) && !vlanmap.get(spvlanid).equals(HypervisorHostHelper.PvlanType.promiscuous)) {
                    String msg = "Specified secondary PVLAN ID " + spvlanid + " is already in use as a " + vlanmap.get(spvlanid).toString() + " VLAN in the DVSwitch";
                    LOGGER.error(msg);
                    throw new Exception(msg);
                }
            }
        }

        // First create a DVSconfig spec.
        VMwareDVSConfigSpec dvsSpec = new VMwareDVSConfigSpec();
        // Next, add the required primary and secondary vlan config specs to the dvs config spec.

        if (!vlanmap.containsKey(vid)) {
            VMwareDVSPvlanConfigSpec ppvlanConfigSpec = createDVPortPvlanConfigSpec(vid, vid, PvlanType.promiscuous, PvlanOperation.add);
            dvsSpec.getPvlanConfigSpec().add(ppvlanConfigSpec);
        }
        if (!vid.equals(spvlanid) && !vlanmap.containsKey(spvlanid)) {
            PvlanType selectedType = StringUtils.isNotBlank(pvlanType) ? PvlanType.fromStr(pvlanType) : PvlanType.isolated;
            VMwareDVSPvlanConfigSpec spvlanConfigSpec = createDVPortPvlanConfigSpec(vid, spvlanid, selectedType, PvlanOperation.add);
            dvsSpec.getPvlanConfigSpec().add(spvlanConfigSpec);
        }

        if (dvsSpec.getPvlanConfigSpec().size() > 0) {
            // We have something to configure on the DVS... so send it the command.
            // When reconfiguring a vmware DVSwitch, we need to send in the configVersion in the spec.
            // Let's retrieve this switch's configVersion first.
            String dvsConfigVersion = dvSwitchMo.getDVSConfigVersion(morDvSwitch);
            dvsSpec.setConfigVersion(dvsConfigVersion);

            // Reconfigure the dvs using this spec.
            try {
                dvSwitchMo.updateVMWareDVSwitchGetTask(morDvSwitch, dvsSpec);
            } catch (AlreadyExistsFaultMsg e) {
                LOGGER.info("Specified vlan id (" + vid + ") private vlan id (" + spvlanid + ") tuple already configured on VMWare DVSwitch");
                // Do nothing, good if the tuple's already configured on the dvswitch.
            } catch (Exception e) {
                // Rethrow the exception
                LOGGER.error("Failed to configure vlan/pvlan tuple on VMware DVSwitch: " + vid + "/" + spvlanid + ", failure message: ", e);
                throw e;
            }
        }

    }

    private static void createPortGroup(String physicalNetwork, String networkName, String vlanRange, Integer vid, Integer spvlanid, DatacenterMO dataCenterMo,
                                        DVSTrafficShapingPolicy shapingPolicy, DVSSecurityPolicy secPolicy, DVSMacManagementPolicy macManagementPolicy,
                                        VMwareDVSPortgroupPolicy portGroupPolicy, DistributedVirtualSwitchMO dvSwitchMo, int numPorts, boolean autoExpandSupported,
                                        boolean dvSwitchSupportNewPolicies)
                    throws Exception {
        VmwareDistributedVirtualSwitchVlanSpec vlanSpec = null;
        VmwareDistributedVirtualSwitchPvlanSpec pvlanSpec = null;
        VMwareDVSPortSetting dvsPortSetting = null;
        DVPortgroupConfigSpec newDvPortGroupSpec;

        // Next, create the port group. For this, we need to create a VLAN spec.
        // NOTE - VmwareDistributedVirtualSwitchPvlanSpec extends VmwareDistributedVirtualSwitchVlanSpec.
        if (vid == null || spvlanid == null) {
            vlanSpec = createDVPortVlanSpec(vid, vlanRange);
            dvsPortSetting = createVmwareDVPortSettingSpec(shapingPolicy, secPolicy, macManagementPolicy, vlanSpec, dvSwitchSupportNewPolicies);
        } else if (spvlanid != null) {
            // Create a pvlan spec. The pvlan spec is different from the pvlan config spec
            // that we created earlier. The pvlan config spec is used to configure the switch
            // with a <primary vlanId, secondary vlanId> tuple. The pvlan spec is used
            // to configure a port group (i.e., a network) with a secondary vlan id. We don't
            // need to mention more than the secondary vlan id because one secondary vlan id
            // can be associated with only one primary vlan id. Give vCenter the secondary vlan id,
            // and it will find out the associated primary vlan id and do the rest of the
            // port group configuration.
            pvlanSpec = createDVPortPvlanIdSpec(spvlanid);
            dvsPortSetting = createVmwareDVPortSettingSpec(shapingPolicy, secPolicy, macManagementPolicy, pvlanSpec, dvSwitchSupportNewPolicies);
        }

        newDvPortGroupSpec = createDvPortGroupSpec(networkName, dvsPortSetting, autoExpandSupported);
        if (portGroupPolicy != null) {
            newDvPortGroupSpec.setPolicy(portGroupPolicy);
        }

        if (!dataCenterMo.hasDvPortGroup(networkName)) {
            LOGGER.info("Distributed Virtual Port group " + networkName + " not found.");
            // TODO(sateesh): Handle Exceptions
            try {
                newDvPortGroupSpec.setNumPorts(numPorts);
                dvSwitchMo.createDVPortGroup(newDvPortGroupSpec);
            } catch (Exception e) {
                String msg = "Failed to create distributed virtual port group " + networkName + " on dvSwitch " + physicalNetwork;
                msg += ". " + VmwareHelper.getExceptionMessage(e);
                throw new Exception(msg);
            }
        } else {
            LOGGER.info("Found Distributed Virtual Port group " + networkName);
            DVPortgroupConfigInfo currentDvPortgroupInfo = dataCenterMo.getDvPortGroupSpec(networkName);
            if (!isSpecMatch(currentDvPortgroupInfo, newDvPortGroupSpec, dvSwitchSupportNewPolicies)) {
                LOGGER.info("Updating Distributed Virtual Port group " + networkName);
                newDvPortGroupSpec.setDefaultPortConfig(dvsPortSetting);
                newDvPortGroupSpec.setConfigVersion(currentDvPortgroupInfo.getConfigVersion());
                ManagedObjectReference morDvPortGroup = dataCenterMo.getDvPortGroupMor(networkName);
                try {
                    dvSwitchMo.updateDvPortGroup(morDvPortGroup, newDvPortGroupSpec);
                } catch (Exception e) {
                    String msg = "Failed to update distributed virtual port group " + networkName + " on dvSwitch " + physicalNetwork;
                    msg += ". " + VmwareHelper.getExceptionMessage(e);
                    throw new Exception(msg);
                }
            }
        }
    }

    private static boolean eitherObjectNull(Object obj1, Object obj2) {
        return (obj1 == null && obj2 != null) || (obj1 != null && obj2 == null);
    }

    private static boolean areBoolPoliciesDifferent(BoolPolicy currentPolicy, BoolPolicy newPolicy) {
        return eitherObjectNull(currentPolicy, newPolicy) ||
                (newPolicy != null && newPolicy.isValue() != currentPolicy.isValue());
    }

    private static boolean areDVSSecurityPoliciesDifferent(DVSSecurityPolicy currentSecurityPolicy, DVSSecurityPolicy newSecurityPolicy) {
        return eitherObjectNull(currentSecurityPolicy, newSecurityPolicy) ||
                (newSecurityPolicy != null &&
                        (areBoolPoliciesDifferent(currentSecurityPolicy.getAllowPromiscuous(), newSecurityPolicy.getAllowPromiscuous()) ||
                                areBoolPoliciesDifferent(currentSecurityPolicy.getForgedTransmits(), newSecurityPolicy.getForgedTransmits()) ||
                                areBoolPoliciesDifferent(currentSecurityPolicy.getMacChanges(), newSecurityPolicy.getMacChanges())));
    }

    private static boolean areDVSMacLearningPoliciesDifferent(DVSMacLearningPolicy currentMacLearningPolicy, DVSMacLearningPolicy newMacLearningPolicy) {
        return eitherObjectNull(currentMacLearningPolicy, newMacLearningPolicy) ||
                (newMacLearningPolicy != null && currentMacLearningPolicy.isEnabled() != newMacLearningPolicy.isEnabled());
    }

    private static boolean areDVSMacManagementPoliciesDifferent(DVSMacManagementPolicy currentMacManagementPolicy, DVSMacManagementPolicy newMacManagementPolicy) {
        return eitherObjectNull(currentMacManagementPolicy, newMacManagementPolicy) ||
                (newMacManagementPolicy != null &&
                        (currentMacManagementPolicy.isAllowPromiscuous() != newMacManagementPolicy.isAllowPromiscuous() ||
                                currentMacManagementPolicy.isForgedTransmits() != newMacManagementPolicy.isForgedTransmits() ||
                                currentMacManagementPolicy.isMacChanges() != newMacManagementPolicy.isMacChanges() ||
                                areDVSMacLearningPoliciesDifferent(currentMacManagementPolicy.getMacLearningPolicy(), newMacManagementPolicy.getMacLearningPolicy())));
    }

    private static boolean isDVSPortConfigSame(String dvPortGroupName, VMwareDVSPortSetting currentPortSetting, VMwareDVSPortSetting newPortSetting, boolean dvSwitchSupportNewPolicies) {
        if (areDVSSecurityPoliciesDifferent(currentPortSetting.getSecurityPolicy(), newPortSetting.getSecurityPolicy())) {
            return false;
        }
        if (dvSwitchSupportNewPolicies && areDVSMacManagementPoliciesDifferent(currentPortSetting.getMacManagementPolicy(), newPortSetting.getMacManagementPolicy())) {
            return false;
        }

        VmwareDistributedVirtualSwitchVlanSpec oldVlanSpec = currentPortSetting.getVlan();
        VmwareDistributedVirtualSwitchVlanSpec newVlanSpec = newPortSetting.getVlan();

        int oldVlanId, newVlanId;
        if (oldVlanSpec instanceof VmwareDistributedVirtualSwitchPvlanSpec && newVlanSpec instanceof VmwareDistributedVirtualSwitchPvlanSpec) {
            VmwareDistributedVirtualSwitchPvlanSpec oldpVlanSpec = (VmwareDistributedVirtualSwitchPvlanSpec) oldVlanSpec;
            VmwareDistributedVirtualSwitchPvlanSpec newpVlanSpec = (VmwareDistributedVirtualSwitchPvlanSpec) newVlanSpec;
            oldVlanId = oldpVlanSpec.getPvlanId();
            newVlanId = newpVlanSpec.getPvlanId();
        } else if (oldVlanSpec instanceof VmwareDistributedVirtualSwitchTrunkVlanSpec && newVlanSpec instanceof VmwareDistributedVirtualSwitchTrunkVlanSpec) {
            VmwareDistributedVirtualSwitchTrunkVlanSpec oldpVlanSpec = (VmwareDistributedVirtualSwitchTrunkVlanSpec) oldVlanSpec;
            VmwareDistributedVirtualSwitchTrunkVlanSpec newpVlanSpec = (VmwareDistributedVirtualSwitchTrunkVlanSpec) newVlanSpec;
            oldVlanId = oldpVlanSpec.getVlanId().get(0).getStart();
            newVlanId = newpVlanSpec.getVlanId().get(0).getStart();
        } else if (oldVlanSpec instanceof VmwareDistributedVirtualSwitchVlanIdSpec && newVlanSpec instanceof VmwareDistributedVirtualSwitchVlanIdSpec) {
            VmwareDistributedVirtualSwitchVlanIdSpec oldVlanIdSpec = (VmwareDistributedVirtualSwitchVlanIdSpec) oldVlanSpec;
            VmwareDistributedVirtualSwitchVlanIdSpec newVlanIdSpec = (VmwareDistributedVirtualSwitchVlanIdSpec) newVlanSpec;
            oldVlanId = oldVlanIdSpec.getVlanId();
            newVlanId = newVlanIdSpec.getVlanId();
        } else {
            LOGGER.debug(String.format("Old and new vlan spec type mismatch found for dvPortGroup: %s. Old spec type is: %s, and new spec type is: %s", dvPortGroupName, oldVlanSpec.getClass(), newVlanSpec.getClass()));
            return false;
        }

        if (oldVlanId != newVlanId) {
            LOGGER.info(String.format("Detected that new VLAN [%d] is different from current VLAN [%d] of dvPortGroup: %s", newVlanId, oldVlanId, dvPortGroupName));
            return false;
        }
        return true;
    }

    public static boolean isSpecMatch(DVPortgroupConfigInfo currentDvPortgroupInfo, DVPortgroupConfigSpec newDvPortGroupSpec, boolean dvSwitchSupportNewPolicies) {
        String dvPortGroupName = newDvPortGroupSpec.getName();
        LOGGER.debug("Checking if configuration of dvPortGroup [" + dvPortGroupName + "] has changed.");
        DVSTrafficShapingPolicy currentTrafficShapingPolicy;
        currentTrafficShapingPolicy = currentDvPortgroupInfo.getDefaultPortConfig().getInShapingPolicy();

        assert (currentTrafficShapingPolicy != null);

        LongPolicy oldAverageBandwidthPolicy = currentTrafficShapingPolicy.getAverageBandwidth();
        LongPolicy oldBurstSizePolicy = currentTrafficShapingPolicy.getBurstSize();
        LongPolicy oldPeakBandwidthPolicy = currentTrafficShapingPolicy.getPeakBandwidth();
        BoolPolicy oldIsEnabledPolicy = currentTrafficShapingPolicy.getEnabled();
        Long oldAverageBandwidth = null;
        Long oldBurstSize = null;
        Long oldPeakBandwidth = null;
        Boolean oldIsEnabled = null;

        if (oldAverageBandwidthPolicy != null) {
            oldAverageBandwidth = oldAverageBandwidthPolicy.getValue();
        }
        if (oldBurstSizePolicy != null) {
            oldBurstSize = oldBurstSizePolicy.getValue();
        }
        if (oldPeakBandwidthPolicy != null) {
            oldPeakBandwidth = oldPeakBandwidthPolicy.getValue();
        }
        if (oldIsEnabledPolicy != null) {
            oldIsEnabled = oldIsEnabledPolicy.isValue();
        }

        DVSTrafficShapingPolicy newTrafficShapingPolicyInbound = newDvPortGroupSpec.getDefaultPortConfig().getInShapingPolicy();
        LongPolicy newAverageBandwidthPolicy = newTrafficShapingPolicyInbound.getAverageBandwidth();
        LongPolicy newBurstSizePolicy = newTrafficShapingPolicyInbound.getBurstSize();
        LongPolicy newPeakBandwidthPolicy = newTrafficShapingPolicyInbound.getPeakBandwidth();
        BoolPolicy newIsEnabledPolicy = newTrafficShapingPolicyInbound.getEnabled();
        Long newAverageBandwidth = null;
        Long newBurstSize = null;
        Long newPeakBandwidth = null;
        Boolean newIsEnabled = null;
        if (newAverageBandwidthPolicy != null) {
            newAverageBandwidth = newAverageBandwidthPolicy.getValue();
        }
        if (newBurstSizePolicy != null) {
            newBurstSize = newBurstSizePolicy.getValue();
        }
        if (newPeakBandwidthPolicy != null) {
            newPeakBandwidth = newPeakBandwidthPolicy.getValue();
        }
        if (newIsEnabledPolicy != null) {
            newIsEnabled = newIsEnabledPolicy.isValue();
        }

        if (!oldIsEnabled.equals(newIsEnabled)) {
            LOGGER.info("Detected change in state of shaping policy (enabled/disabled) [" + newIsEnabled + "]");
            return false;
        }

        if (oldIsEnabled || newIsEnabled) {
            if (oldAverageBandwidth != null && !oldAverageBandwidth.equals(newAverageBandwidth)) {
                LOGGER.info("Average bandwidth setting in new shaping policy doesn't match the existing setting.");
                return false;
            } else if (oldBurstSize != null && !oldBurstSize.equals(newBurstSize)) {
                LOGGER.info("Burst size setting in new shaping policy doesn't match the existing setting.");
                return false;
            } else if (oldPeakBandwidth != null && !oldPeakBandwidth.equals(newPeakBandwidth)) {
                LOGGER.info("Peak bandwidth setting in new shaping policy doesn't match the existing setting.");
                return false;
            }
        }

        boolean oldAutoExpandSetting = currentDvPortgroupInfo.isAutoExpand();
        boolean autoExpandEnabled = newDvPortGroupSpec.isAutoExpand();
        if (oldAutoExpandSetting != autoExpandEnabled) {
            return false;
        }
        if (!autoExpandEnabled) {
            // Allow update of number of dvports per dvPortGroup is auto expand is not enabled.
            int oldNumPorts = currentDvPortgroupInfo.getNumPorts();
            int newNumPorts = newDvPortGroupSpec.getNumPorts();
            if (oldNumPorts < newNumPorts) {
                LOGGER.info("Need to update the number of dvports for dvPortGroup :[" + dvPortGroupName +
                            "] from existing number of dvports " + oldNumPorts + " to " + newNumPorts);
                return false;
            } else if (oldNumPorts > newNumPorts) {
                LOGGER.warn("Detected that new number of dvports [" + newNumPorts + "] in dvPortGroup [" + dvPortGroupName +
                        "] is less than existing number of dvports [" + oldNumPorts + "]. Attempt to update this dvPortGroup may fail!");
                return false;
            }
        }

        VMwareDVSPortSetting currentPortSetting = ((VMwareDVSPortSetting)currentDvPortgroupInfo.getDefaultPortConfig());
        VMwareDVSPortSetting newPortSetting = ((VMwareDVSPortSetting)newDvPortGroupSpec.getDefaultPortConfig());
        return isDVSPortConfigSame(dvPortGroupName, currentPortSetting, newPortSetting, dvSwitchSupportNewPolicies);
    }

    public static ManagedObjectReference waitForDvPortGroupReady(DatacenterMO dataCenterMo, String dvPortGroupName, long timeOutMs) throws Exception {
        ManagedObjectReference morDvPortGroup = null;

        // if DvPortGroup is just created, we may fail to retrieve it, we
        // need to retry
        long startTick = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTick <= timeOutMs) {
            morDvPortGroup = dataCenterMo.getDvPortGroupMor(dvPortGroupName);
            if (morDvPortGroup != null) {
                break;
            }

            LOGGER.info("Waiting for dvPortGroup " + dvPortGroupName + " to be ready");
            Thread.sleep(1000);
        }
        return morDvPortGroup;
    }

    public static boolean isSpecMatch(DVPortgroupConfigInfo configInfo, Integer vid, DVSTrafficShapingPolicy shapingPolicy) {
        DVSTrafficShapingPolicy currentTrafficShapingPolicy;
        currentTrafficShapingPolicy = configInfo.getDefaultPortConfig().getInShapingPolicy();

        assert (currentTrafficShapingPolicy != null);

        LongPolicy averageBandwidth = currentTrafficShapingPolicy.getAverageBandwidth();
        LongPolicy burstSize = currentTrafficShapingPolicy.getBurstSize();
        LongPolicy peakBandwidth = currentTrafficShapingPolicy.getPeakBandwidth();
        BoolPolicy isEnabled = currentTrafficShapingPolicy.getEnabled();

        if (!isEnabled.equals(shapingPolicy.getEnabled())) {
            return false;
        }

        if (averageBandwidth != null && !averageBandwidth.equals(shapingPolicy.getAverageBandwidth())) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Average bandwidth setting in shaping policy doesn't match with existing setting.");
            }
            return false;
        } else if (burstSize != null && !burstSize.equals(shapingPolicy.getBurstSize())) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Burst size setting in shaping policy doesn't match with existing setting.");
            }
            return false;
        } else if (peakBandwidth != null && !peakBandwidth.equals(shapingPolicy.getPeakBandwidth())) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Peak bandwidth setting in shaping policy doesn't match with existing setting.");
            }
            return false;
        }

        return true;
    }

    public static DVPortgroupConfigSpec createDvPortGroupSpec(String dvPortGroupName, DVPortSetting portSetting, boolean autoExpandSupported) {
        DVPortgroupConfigSpec spec = new DVPortgroupConfigSpec();
        spec.setName(dvPortGroupName);
        spec.setDefaultPortConfig(portSetting);
        spec.setPortNameFormat("vnic<portIndex>");
        spec.setType("earlyBinding");
        spec.setAutoExpand(autoExpandSupported);
        return spec;
    }

    public static VMwareDVSPortSetting createVmwareDVPortSettingSpec(DVSTrafficShapingPolicy shapingPolicy, DVSSecurityPolicy secPolicy,
            DVSMacManagementPolicy macManagementPolicy, VmwareDistributedVirtualSwitchVlanSpec vlanSpec, boolean dvSwitchSupportNewPolicies) {
        VMwareDVSPortSetting dvsPortSetting = new VMwareDVSPortSetting();
        dvsPortSetting.setVlan(vlanSpec);
        dvsPortSetting.setSecurityPolicy(secPolicy);
        if (dvSwitchSupportNewPolicies) {
            dvsPortSetting.setMacManagementPolicy(macManagementPolicy);
        }
        dvsPortSetting.setInShapingPolicy(shapingPolicy);
        dvsPortSetting.setOutShapingPolicy(shapingPolicy);
        return dvsPortSetting;
    }

    public static DVSTrafficShapingPolicy getDVSShapingPolicy(Integer networkRateMbps) {
        DVSTrafficShapingPolicy shapingPolicy = new DVSTrafficShapingPolicy();
        BoolPolicy isEnabled = new BoolPolicy();
        if (networkRateMbps == null || networkRateMbps.intValue() <= 0) {
            isEnabled.setValue(false);
            shapingPolicy.setEnabled(isEnabled);
            return shapingPolicy;
        }
        LongPolicy averageBandwidth = new LongPolicy();
        LongPolicy peakBandwidth = new LongPolicy();
        LongPolicy burstSize = new LongPolicy();

        isEnabled.setValue(true);
        averageBandwidth.setValue(networkRateMbps.intValue() * 1024L * 1024L);
        // We chose 50% higher allocation than average bandwidth.
        // TODO(sateesh): Also let user specify the peak coefficient
        peakBandwidth.setValue((long)(averageBandwidth.getValue() * 1.5));
        // TODO(sateesh): Also let user specify the burst coefficient
        burstSize.setValue(5 * averageBandwidth.getValue() / 8);

        shapingPolicy.setEnabled(isEnabled);
        shapingPolicy.setAverageBandwidth(averageBandwidth);
        shapingPolicy.setPeakBandwidth(peakBandwidth);
        shapingPolicy.setBurstSize(burstSize);

        return shapingPolicy;
    }

    public static VmwareDistributedVirtualSwitchPvlanSpec createDVPortPvlanIdSpec(int pvlanId) {
        VmwareDistributedVirtualSwitchPvlanSpec pvlanIdSpec = new VmwareDistributedVirtualSwitchPvlanSpec();
        pvlanIdSpec.setPvlanId(pvlanId);
        return pvlanIdSpec;
    }

    public enum PvlanOperation {
        add, edit, remove
    }

    public enum PvlanType {
        promiscuous, isolated, community;

        public static PvlanType fromStr(String val) {
            if (StringUtils.isBlank(val)) {
                return null;
            } else if (val.equalsIgnoreCase("promiscuous")) {
                return promiscuous;
            } else if (val.equalsIgnoreCase("community")) {
                return community;
            } else if (val.equalsIgnoreCase("isolated")) {
                return isolated;
            }
            return null;
        }
    }

    public static VMwareDVSPvlanConfigSpec createDVPortPvlanConfigSpec(int vlanId, int secondaryVlanId, PvlanType pvlantype, PvlanOperation operation) {
        VMwareDVSPvlanConfigSpec pvlanConfigSpec = new VMwareDVSPvlanConfigSpec();
        VMwareDVSPvlanMapEntry map = new VMwareDVSPvlanMapEntry();
        map.setPvlanType(pvlantype.toString());
        map.setPrimaryVlanId(vlanId);
        map.setSecondaryVlanId(secondaryVlanId);
        pvlanConfigSpec.setPvlanEntry(map);

        pvlanConfigSpec.setOperation(operation.toString());
        return pvlanConfigSpec;
    }

    public static VmwareDistributedVirtualSwitchVlanSpec createDVPortVlanSpec(Integer vlanId, String vlanRange) {
        if (vlanId != null && vlanId == 4095){
            vlanId = null;
            vlanRange = "0-4094";
        }
        if (vlanId == null && vlanRange != null && !vlanRange.isEmpty()) {
            LOGGER.debug("Creating dvSwitch port vlan-trunk spec with range: " + vlanRange);
            VmwareDistributedVirtualSwitchTrunkVlanSpec trunkVlanSpec = new VmwareDistributedVirtualSwitchTrunkVlanSpec();
            String vlanRangeUpdated = com.cloud.utils.StringUtils.numbersToRange(vlanRange);
            for (final String vlanRangePart : vlanRangeUpdated.split(",")) {
                if (vlanRangePart == null || vlanRangePart.isEmpty()) {
                    continue;
                }
                final NumericRange numericRange = new NumericRange();
                if (vlanRangePart.contains("-")) {
                    final String[] range = vlanRangePart.split("-");
                    if (range.length == 2 && range[0] != null && range[1] != null) {
                        numericRange.setStart(NumbersUtil.parseInt(range[0], 0));
                        numericRange.setEnd(NumbersUtil.parseInt(range[1], 0));
                    } else {
                        continue;
                    }
                } else {
                    numericRange.setStart(NumbersUtil.parseInt(vlanRangePart, 0));
                    numericRange.setEnd(NumbersUtil.parseInt(vlanRangePart, 0));
                }
                trunkVlanSpec.getVlanId().add(numericRange);
            }
            if (trunkVlanSpec.getVlanId().size() != 0) {
                return trunkVlanSpec;
            }
        }
        VmwareDistributedVirtualSwitchVlanIdSpec vlanIdSpec = new VmwareDistributedVirtualSwitchVlanIdSpec();
        vlanIdSpec.setVlanId(vlanId == null ? 0 : vlanId);
        LOGGER.debug("Creating dvSwitch port vlan-id spec with id: " + vlanIdSpec.getVlanId());
        return vlanIdSpec;
    }

    public static Map<NetworkOffering.Detail, String> getDefaultSecurityDetails() {
        final Map<NetworkOffering.Detail, String> details = new HashMap<>();
        details.put(NetworkOffering.Detail.PromiscuousMode, NetworkOrchestrationService.PromiscuousMode.value().toString());
        details.put(NetworkOffering.Detail.MacAddressChanges, NetworkOrchestrationService.MacAddressChanges.value().toString());
        details.put(NetworkOffering.Detail.ForgedTransmits, NetworkOrchestrationService.ForgedTransmits.value().toString());
        details.put(NetworkOffering.Detail.MacLearning, NetworkOrchestrationService.MacLearning.value().toString());
        return details;
    }

    public static DVSSecurityPolicy createDVSSecurityPolicy(Map<NetworkOffering.Detail, String> nicDetails) {
        DVSSecurityPolicy secPolicy = new DVSSecurityPolicy();
        BoolPolicy allow = new BoolPolicy();
        allow.setValue(true);
        BoolPolicy deny = new BoolPolicy();
        deny.setValue(false);
        secPolicy.setAllowPromiscuous(deny);
        secPolicy.setForgedTransmits(allow);
        secPolicy.setMacChanges(allow);
        if (nicDetails == null) {
            nicDetails = getDefaultSecurityDetails();
        }
        if (nicDetails.containsKey(NetworkOffering.Detail.PromiscuousMode)) {
            if (Boolean.parseBoolean(nicDetails.get(NetworkOffering.Detail.PromiscuousMode))) {
                secPolicy.setAllowPromiscuous(allow);
            } else {
                secPolicy.setAllowPromiscuous(deny);
            }
        }
        if (nicDetails.containsKey(NetworkOffering.Detail.ForgedTransmits)) {
            if (Boolean.parseBoolean(nicDetails.get(NetworkOffering.Detail.ForgedTransmits))) {
                secPolicy.setForgedTransmits(allow);
            } else {
                secPolicy.setForgedTransmits(deny);
            }
        }
        if (nicDetails.containsKey(NetworkOffering.Detail.MacAddressChanges)) {
            if (Boolean.parseBoolean(nicDetails.get(NetworkOffering.Detail.MacAddressChanges))) {
                secPolicy.setMacChanges(allow);
            } else {
                secPolicy.setMacChanges(deny);
            }
        }
        return secPolicy;
    }

    public static DVSMacManagementPolicy createDVSMacManagementPolicy(Map<NetworkOffering.Detail, String> nicDetails) {
        if (nicDetails == null) {
            nicDetails = getDefaultSecurityDetails();
        }
        DVSMacManagementPolicy macManagementPolicy = new DVSMacManagementPolicy();
        macManagementPolicy.setAllowPromiscuous(Boolean.valueOf(nicDetails.getOrDefault(NetworkOffering.Detail.PromiscuousMode, "false")));
        macManagementPolicy.setForgedTransmits(Boolean.valueOf(nicDetails.getOrDefault(NetworkOffering.Detail.ForgedTransmits, "false")));
        macManagementPolicy.setMacChanges(Boolean.valueOf(nicDetails.getOrDefault(NetworkOffering.Detail.MacAddressChanges, "false")));
        DVSMacLearningPolicy macLearningPolicy = new DVSMacLearningPolicy();
        macLearningPolicy.setEnabled(Boolean.parseBoolean(nicDetails.getOrDefault(NetworkOffering.Detail.MacLearning, "false")));
        macManagementPolicy.setMacLearningPolicy(macLearningPolicy);
        return macManagementPolicy;
    }

    public static HostNetworkSecurityPolicy createVSSecurityPolicy(Map<NetworkOffering.Detail, String> nicDetails) {
        HostNetworkSecurityPolicy secPolicy = new HostNetworkSecurityPolicy();
        secPolicy.setAllowPromiscuous(Boolean.FALSE);
        secPolicy.setForgedTransmits(Boolean.TRUE);
        secPolicy.setMacChanges(Boolean.TRUE);

        if (nicDetails == null) {
            nicDetails = getDefaultSecurityDetails();
        }

        if (nicDetails.containsKey(NetworkOffering.Detail.PromiscuousMode)) {
            secPolicy.setAllowPromiscuous(Boolean.valueOf(nicDetails.get(NetworkOffering.Detail.PromiscuousMode)));
        }

        if (nicDetails.containsKey(NetworkOffering.Detail.ForgedTransmits)) {
            secPolicy.setForgedTransmits(Boolean.valueOf(nicDetails.get(NetworkOffering.Detail.ForgedTransmits)));
        }

        if (nicDetails.containsKey(NetworkOffering.Detail.MacAddressChanges)) {
            secPolicy.setMacChanges(Boolean.valueOf(nicDetails.get(NetworkOffering.Detail.MacAddressChanges)));
        }

        return secPolicy;
    }

    public static Pair<ManagedObjectReference, String> prepareNetwork(String vSwitchName, String namePrefix, HostMO hostMo, String vlanId, Integer networkRateMbps,
                                                                      Integer networkRateMulticastMbps, long timeOutMs, boolean syncPeerHosts, BroadcastDomainType broadcastDomainType,
                                                                      String nicUuid, Map<NetworkOffering.Detail, String> nicDetails) throws Exception {

        HostVirtualSwitch vSwitch;
        if (vSwitchName == null) {
            LOGGER.info("Detected vswitch name as undefined. Defaulting to vSwitch0");
            vSwitchName = "vSwitch0";
        }
        vSwitch = hostMo.getHostVirtualSwitchByName(vSwitchName);

        if (vSwitch == null) {
            String msg = "Unable to find vSwitch" + vSwitchName;
            LOGGER.error(msg);
            throw new Exception(msg);
        }

        boolean createGCTag = false;
        String networkName;
        Integer vid = null;

        /** This is the list of BroadcastDomainTypes we can actually
         * prepare networks for in this function.
         */
        BroadcastDomainType[] supportedBroadcastTypes =
                new BroadcastDomainType[] {BroadcastDomainType.Lswitch, BroadcastDomainType.LinkLocal, BroadcastDomainType.Native, BroadcastDomainType.Pvlan,
                BroadcastDomainType.Storage, BroadcastDomainType.UnDecided, BroadcastDomainType.Vlan, BroadcastDomainType.NSX};

        if (!Arrays.asList(supportedBroadcastTypes).contains(broadcastDomainType)) {
            throw new InvalidParameterValueException("BroadcastDomainType " + broadcastDomainType + " it not supported on a VMWare hypervisor at this time.");
        }

        if (broadcastDomainType == BroadcastDomainType.Lswitch) {
            /**
             * Nicira NVP requires each vm to have its own port-group with a dedicated
             * vlan. We'll set the name of the pg to the uuid of the nic.
             */
            networkName = nicUuid;
            // No doubt about this, depending on vid=null to avoid lots of code below
            vid = null;
        } else {
            networkName = composeCloudNetworkName(namePrefix, vlanId, null, networkRateMbps, vSwitchName, VirtualSwitchType.StandardVirtualSwitch);

            if (vlanId != null && !UNTAGGED_VLAN_NAME.equalsIgnoreCase(vlanId)) {
                createGCTag = true;
                vid = Integer.parseInt(vlanId);
            }
        }

        HostNetworkSecurityPolicy secPolicy = createVSSecurityPolicy(nicDetails);

        HostNetworkTrafficShapingPolicy shapingPolicy = null;
        if (networkRateMbps != null && networkRateMbps.intValue() > 0) {
            shapingPolicy = new HostNetworkTrafficShapingPolicy();
            shapingPolicy.setEnabled(true);
            shapingPolicy.setAverageBandwidth(networkRateMbps.intValue() * 1024L * 1024L);

            //
            // TODO : people may have different opinion on how to set the following
            //

            // give 50% premium to peek
            shapingPolicy.setPeakBandwidth((long)(shapingPolicy.getAverageBandwidth() * 1.5));

            // allow 5 seconds of burst transfer
            shapingPolicy.setBurstSize(5 * shapingPolicy.getAverageBandwidth() / 8);
        }

        boolean bWaitPortGroupReady = false;
        if (broadcastDomainType == BroadcastDomainType.Lswitch) {
            //if NSX API VERSION >= 4.2, connect to br-int (nsx.network), do not create portgroup else previous behaviour
            if (NiciraNvpApiVersion.isApiVersionLowerThan("4.2")){
              //Previous behaviour
                if (!hostMo.hasPortGroup(vSwitch, networkName)) {
                    createNvpPortGroup(hostMo, vSwitch, networkName, shapingPolicy);

                    bWaitPortGroupReady = true;
                } else {
                    bWaitPortGroupReady = false;
                }
            }
        } else {
            if (!hostMo.hasPortGroup(vSwitch, networkName)) {
                hostMo.createPortGroup(vSwitch, networkName, vid, secPolicy, shapingPolicy, timeOutMs);
                // Setting flag "bWaitPortGroupReady" to false.
                // This flag indicates whether we need to wait for portgroup on vCenter.
                // Above createPortGroup() method itself ensures creation of portgroup as well as wait for portgroup.
                bWaitPortGroupReady = false;
            } else {
                HostPortGroupSpec spec = hostMo.getPortGroupSpec(networkName);
                if (!isSpecMatch(spec, vid, secPolicy, shapingPolicy)) {
                    hostMo.updatePortGroup(vSwitch, networkName, vid, secPolicy, shapingPolicy);
                    bWaitPortGroupReady = true;
                }
            }
        }

        ManagedObjectReference morNetwork = null;

        if (broadcastDomainType != BroadcastDomainType.Lswitch ||
                (broadcastDomainType == BroadcastDomainType.Lswitch && NiciraNvpApiVersion.isApiVersionLowerThan("4.2"))) {
            if (bWaitPortGroupReady)
                morNetwork = waitForNetworkReady(hostMo, networkName, timeOutMs);
            else
                morNetwork = hostMo.getNetworkMor(networkName);
            if (morNetwork == null) {
                String msg = "Failed to create guest network " + networkName;
                LOGGER.error(msg);
                throw new Exception(msg);
            }

            if (createGCTag) {
                NetworkMO networkMo = new NetworkMO(hostMo.getContext(), morNetwork);
                networkMo.setCustomFieldValue(CustomFieldConstants.CLOUD_GC, "true");
            }
        }

        if (syncPeerHosts) {
            ManagedObjectReference morParent = hostMo.getParentMor();
            if (morParent != null && morParent.getType().equals("ClusterComputeResource")) {
                // to be conservative, lock cluster
                GlobalLock lock = GlobalLock.getInternLock("ClusterLock." + morParent.getValue());
                try {
                    if (lock.lock(DEFAULT_LOCK_TIMEOUT_SECONDS)) {
                        try {
                            List<ManagedObjectReference> hosts = hostMo.getContext().getVimClient().getDynamicProperty(morParent, "host");
                            if (hosts != null) {
                                for (ManagedObjectReference otherHost : hosts) {
                                    if (!otherHost.getValue().equals(hostMo.getMor().getValue())) {
                                        HostMO otherHostMo = new HostMO(hostMo.getContext(), otherHost);
                                        try {
                                            if (LOGGER.isDebugEnabled())
                                                LOGGER.debug("Prepare network on other host, vlan: " + vlanId + ", host: " + otherHostMo.getHostName());
                                            prepareNetwork(vSwitchName, namePrefix, otherHostMo, vlanId, networkRateMbps, networkRateMulticastMbps, timeOutMs, false,
                                                    broadcastDomainType, nicUuid, nicDetails);
                                        } catch (Exception e) {
                                            LOGGER.warn("Unable to prepare network on other host, vlan: " + vlanId + ", host: " + otherHostMo.getHostName());
                                        }
                                    }
                                }
                            }
                        } finally {
                            lock.unlock();
                        }
                    } else {
                        LOGGER.warn("Unable to lock cluster to prepare guest network, vlan: " + vlanId);
                    }
                } finally {
                    lock.releaseRef();
                }
            }
        }

        LOGGER.info("Network " + networkName + " is ready on vSwitch " + vSwitchName);
        return new Pair<ManagedObjectReference, String>(morNetwork, networkName);
    }

    private static boolean isSpecMatch(HostPortGroupSpec spec, Integer vlanId, HostNetworkSecurityPolicy securityPolicy, HostNetworkTrafficShapingPolicy shapingPolicy) {
        // check VLAN configuration
        if (vlanId != null) {
            if (vlanId.intValue() != spec.getVlanId())
                return false;
        } else {
            if (spec.getVlanId() != 0)
                return false;
        }

        // check security policy for the portgroup
        HostNetworkSecurityPolicy secPolicyInSpec = null;
        if (spec.getPolicy() != null) {
            secPolicyInSpec = spec.getPolicy().getSecurity();
        }

        if ((secPolicyInSpec != null && securityPolicy == null) || (secPolicyInSpec == null && securityPolicy != null)) {
            return false;
        }

        if (secPolicyInSpec != null
                && ((securityPolicy.isAllowPromiscuous() != null && !securityPolicy.isAllowPromiscuous().equals(secPolicyInSpec.isAllowPromiscuous()))
                    || (securityPolicy.isForgedTransmits() != null && !securityPolicy.isForgedTransmits().equals(secPolicyInSpec.isForgedTransmits()))
                    || (securityPolicy.isMacChanges() != null && !securityPolicy.isMacChanges().equals(secPolicyInSpec.isMacChanges())))) {
            return false;
        }

        // check traffic shaping configuration
        HostNetworkTrafficShapingPolicy policyInSpec = null;
        if (spec.getPolicy() != null) {
            policyInSpec = spec.getPolicy().getShapingPolicy();
        }

        if ((policyInSpec != null && shapingPolicy == null) || (policyInSpec == null && shapingPolicy != null)) {
            return false;
        }

        if (policyInSpec == null && shapingPolicy == null) {
            return true;
        }

        // so far policyInSpec and shapingPolicy should both not be null
        if (policyInSpec.isEnabled() == null || !policyInSpec.isEnabled().booleanValue())
            return false;

        if (policyInSpec.getAverageBandwidth() == null || policyInSpec.getAverageBandwidth().longValue() != shapingPolicy.getAverageBandwidth().longValue())
            return false;

        if (policyInSpec.getPeakBandwidth() == null || policyInSpec.getPeakBandwidth().longValue() != shapingPolicy.getPeakBandwidth().longValue())
            return false;

        if (policyInSpec.getBurstSize() == null || policyInSpec.getBurstSize().longValue() != shapingPolicy.getBurstSize().longValue())
            return false;

        return true;
    }

    private static void createNvpPortGroup(HostMO hostMo, HostVirtualSwitch vSwitch, String networkName, HostNetworkTrafficShapingPolicy shapingPolicy) throws Exception {
        /**
         * No portgroup created yet for this nic
         * We need to find an unused vlan and create the pg
         * The vlan is limited to this vSwitch and the NVP vAPP,
         * so no relation to the other vlans in use in CloudStack.
         */
        String vSwitchName = vSwitch.getName();

        // Find all vlanids that we have in use
        List<Integer> usedVlans = new ArrayList<Integer>();
        for (HostPortGroup pg : hostMo.getHostNetworkInfo().getPortgroup()) {
            HostPortGroupSpec hpgs = pg.getSpec();
            if (vSwitchName.equals(hpgs.getVswitchName()))
                usedVlans.add(hpgs.getVlanId());
        }

        // Find the first free vlanid
        int nvpVlanId = 0;
        for (nvpVlanId = 1; nvpVlanId < 4095; nvpVlanId++) {
            if (!usedVlans.contains(nvpVlanId)) {
                break;
            }
        }
        if (nvpVlanId == 4095) {
            throw new InvalidParameterValueException("No free vlan numbers on " + vSwitchName + " to create a portgroup for nic " + networkName);
        }

        // Strict security policy
        HostNetworkSecurityPolicy secPolicy = new HostNetworkSecurityPolicy();
        secPolicy.setAllowPromiscuous(Boolean.FALSE);
        secPolicy.setForgedTransmits(Boolean.FALSE);
        secPolicy.setMacChanges(Boolean.FALSE);

        // Create a portgroup with the uuid of the nic and the vlanid found above
        hostMo.createPortGroup(vSwitch, networkName, nvpVlanId, secPolicy, shapingPolicy);
    }

    public static ManagedObjectReference waitForNetworkReady(HostMO hostMo, String networkName, long timeOutMs) throws Exception {

        ManagedObjectReference morNetwork = null;

        // if portGroup is just created, getNetwork may fail to retrieve it, we
        // need to retry
        long startTick = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTick <= timeOutMs) {
            morNetwork = hostMo.getNetworkMor(networkName);
            if (morNetwork != null) {
                break;
            }

            LOGGER.info("Waiting for network " + networkName + " to be ready");
            Thread.sleep(1000);
        }

        return morNetwork;
    }

    public static boolean createBlankVm(VmwareHypervisorHost host, String vmName, String vmInternalCSName, int cpuCount, int cpuSpeedMHz, int cpuReservedMHz,
                                        boolean limitCpuUse, int memoryMB, int memoryReserveMB, String guestOsIdentifier, ManagedObjectReference morDs, boolean snapshotDirToParent,
                                        Pair<String, String> controllerInfo, Boolean systemVm) throws Exception {

        if (LOGGER.isInfoEnabled())
            LOGGER.info("Create blank VM. cpuCount: " + cpuCount + ", cpuSpeed(MHz): " + cpuSpeedMHz + ", mem(Mb): " + memoryMB);

        VirtualDeviceConfigSpec controllerSpec = null;
        // VM config basics
        VirtualMachineConfigSpec vmConfig = new VirtualMachineConfigSpec();
        vmConfig.setName(vmName);
        if (vmInternalCSName == null)
            vmInternalCSName = vmName;

        VmwareHelper.setBasicVmConfig(vmConfig, cpuCount, cpuSpeedMHz, cpuReservedMHz, memoryMB, memoryReserveMB, guestOsIdentifier, limitCpuUse, false);

        Pair<String, String> chosenDiskControllers = VmwareHelper.chooseRequiredDiskControllers(controllerInfo, null, host, guestOsIdentifier);
        String scsiDiskController = HypervisorHostHelper.getScsiController(chosenDiskControllers);
        // If there is requirement for a SCSI controller, ensure to create those.
        if (scsiDiskController != null) {
        int busNum = 0;
            int maxControllerCount = VmwareHelper.MAX_SCSI_CONTROLLER_COUNT;
            if (systemVm) {
                maxControllerCount = 1;
            }
            while (busNum < maxControllerCount) {
            VirtualDeviceConfigSpec scsiControllerSpec = new VirtualDeviceConfigSpec();
                scsiControllerSpec = getControllerSpec(DiskControllerType.getType(scsiDiskController).toString(), busNum);

            vmConfig.getDeviceChange().add(scsiControllerSpec);
            busNum++;
            }
        }

        if (guestOsIdentifier.startsWith("darwin")) { //Mac OS
            LOGGER.debug("Add USB Controller device for blank Mac OS VM " + vmName);

            //For Mac OS X systems, the EHCI+UHCI controller is enabled by default and is required for USB mouse and keyboard access.
            VirtualDevice usbControllerDevice = VmwareHelper.prepareUSBControllerDevice();
            VirtualDeviceConfigSpec usbControllerSpec = new VirtualDeviceConfigSpec();
            usbControllerSpec.setDevice(usbControllerDevice);
            usbControllerSpec.setOperation(VirtualDeviceConfigSpecOperation.ADD);

            vmConfig.getDeviceChange().add(usbControllerSpec);
        }

        VirtualMachineFileInfo fileInfo = new VirtualMachineFileInfo();
        DatastoreMO dsMo = new DatastoreMO(host.getContext(), morDs);
        fileInfo.setVmPathName(String.format("[%s]", dsMo.getName()));
        vmConfig.setFiles(fileInfo);

        VirtualMachineVideoCard videoCard = new VirtualMachineVideoCard();
        videoCard.setControllerKey(100);
        videoCard.setUseAutoDetect(true);

        VirtualDeviceConfigSpec videoDeviceSpec = new VirtualDeviceConfigSpec();
        videoDeviceSpec.setDevice(videoCard);
        videoDeviceSpec.setOperation(VirtualDeviceConfigSpecOperation.ADD);

        vmConfig.getDeviceChange().add(videoDeviceSpec);

        ClusterMO clusterMo = new ClusterMO(host.getContext(), host.getHyperHostCluster());
        DatacenterMO dataCenterMo = new DatacenterMO(host.getContext(), host.getHyperHostDatacenter());
        setVMHardwareVersion(vmConfig, clusterMo, dataCenterMo);

        LOGGER.debug(LogUtils.logGsonWithoutException("Creating blank VM with configuration [%s].", vmConfig));
        if (host.createVm(vmConfig)) {
            // Here, when attempting to find the VM, we need to use the name
            // with which we created it. This is the only such place where
            // we need to do this. At all other places, we always use the
            // VM's internal cloudstack generated name. Here, we cannot use
            // the internal name because we can set the internal name into the
            // VM's custom field CLOUD_VM_INTERNAL_NAME only after we create
            // the VM.
            VirtualMachineMO vmMo = host.findVmOnHyperHost(vmName);
            assert (vmMo != null);

            vmMo.setCustomFieldValue(CustomFieldConstants.CLOUD_VM_INTERNAL_NAME, vmInternalCSName);

            int ideControllerKey = -1;
            while (ideControllerKey < 0) {
                ideControllerKey = vmMo.tryGetIDEDeviceControllerKey();
                if (ideControllerKey >= 0)
                    break;

                LOGGER.info("Waiting for IDE controller be ready in VM: " + vmInternalCSName);
                Thread.sleep(1000);
            }

            return true;
        }
        return false;
    }

    /**
     * Set the VM hardware version based on the information retrieved by the cluster and datacenter:
     * - If the cluster hardware version is set, then it is set to this hardware version on vmConfig
     * - If the cluster hardware version is not set, check datacenter hardware version. If it is set, then it is set to vmConfig
     * - In case both cluster and datacenter hardware version are not set, hardware version is not set to vmConfig
     */
    public static void setVMHardwareVersion(VirtualMachineConfigSpec vmConfig, ClusterMO clusterMO, DatacenterMO datacenterMO) throws Exception {
        String version = getNewVMHardwareVersion(clusterMO, datacenterMO);
        if (StringUtils.isNotBlank(version)) {
            vmConfig.setVersion(version);
        }
    }

    /**
     * Return the VM hardware version based on the information retrieved by the cluster and datacenter:
     * - If the cluster hardware version is set, then return this hardware version
     * - If the cluster hardware version is not set, check datacenter hardware version. If it is set, then return it
     * - In case both cluster and datacenter hardware version are not set, return null
     */
    public static String getNewVMHardwareVersion(ClusterMO clusterMO, DatacenterMO datacenterMO) throws Exception {
        String version = null;
        ClusterConfigInfoEx clusterConfigInfo = clusterMO != null ? clusterMO.getClusterConfigInfo() : null;
        String clusterHardwareVersion = clusterConfigInfo != null ? clusterConfigInfo.getDefaultHardwareVersionKey() : null;
        if (StringUtils.isNotBlank(clusterHardwareVersion)) {
            LOGGER.debug("Cluster hardware version found: " + clusterHardwareVersion + ". Creating VM with this hardware version");
            version = clusterHardwareVersion;
        } else {
            DatacenterConfigInfo datacenterConfigInfo = datacenterMO != null ? datacenterMO.getDatacenterConfigInfo() : null;
            String datacenterHardwareVersion = datacenterConfigInfo != null ? datacenterConfigInfo.getDefaultHardwareVersionKey() : null;
            if (StringUtils.isNotBlank(datacenterHardwareVersion)) {
                LOGGER.debug("Datacenter hardware version found: " + datacenterHardwareVersion + ". Creating VM with this hardware version");
                version = datacenterHardwareVersion;
            }
        }
        return version;
    }

    private static VirtualDeviceConfigSpec getControllerSpec(String diskController, int busNum) {
        VirtualDeviceConfigSpec controllerSpec = new VirtualDeviceConfigSpec();
        VirtualController controller = null;

        if (diskController.equalsIgnoreCase(DiskControllerType.ide.toString())) {
           controller = new VirtualIDEController();
        } else if (DiskControllerType.pvscsi == DiskControllerType.getType(diskController)) {
            controller = new ParaVirtualSCSIController();
        } else if (DiskControllerType.lsisas1068 == DiskControllerType.getType(diskController)) {
            controller = new VirtualLsiLogicSASController();
        } else if (DiskControllerType.buslogic == DiskControllerType.getType(diskController)) {
            controller = new VirtualBusLogicController();
        } else if (DiskControllerType.lsilogic == DiskControllerType.getType(diskController)) {
            controller = new VirtualLsiLogicController();
        }

        if (!diskController.equalsIgnoreCase(DiskControllerType.ide.toString())) {
            ((VirtualSCSIController)controller).setSharedBus(VirtualSCSISharing.NO_SHARING);
        }

        controller.setBusNumber(busNum);
        controller.setKey(busNum - VmwareHelper.MAX_SCSI_CONTROLLER_COUNT);

        controllerSpec.setDevice(controller);
        controllerSpec.setOperation(VirtualDeviceConfigSpecOperation.ADD);

        return controllerSpec;
    }
    public static VirtualMachineMO createWorkerVM(VmwareHypervisorHost hyperHost, DatastoreMO dsMo, String vmName, String vmxFormattedHardwareVersion) throws Exception {

        // Allow worker VM to float within cluster so that we will have better chance to
        // create it successfully
        ManagedObjectReference morCluster = hyperHost.getHyperHostCluster();
        if (morCluster != null)
            hyperHost = new ClusterMO(hyperHost.getContext(), morCluster);

        if (dsMo.getDatastoreType().equalsIgnoreCase("VVOL") && !vmName.startsWith(CustomFieldConstants.CLOUD_UUID)) {
            vmName = CustomFieldConstants.CLOUD_UUID + "-" + vmName;
        }
        VirtualMachineMO workingVM = null;
        VirtualMachineConfigSpec vmConfig = new VirtualMachineConfigSpec();
        vmConfig.setName(vmName);
        if (StringUtils.isNotBlank(vmxFormattedHardwareVersion)){
            vmConfig.setVersion(vmxFormattedHardwareVersion);
        }  else {
            ClusterMO clusterMo = new ClusterMO(hyperHost.getContext(), hyperHost.getHyperHostCluster());
            DatacenterMO dataCenterMo = new DatacenterMO(hyperHost.getContext(), hyperHost.getHyperHostDatacenter());
            setVMHardwareVersion(vmConfig, clusterMo, dataCenterMo);
        }
        vmConfig.setMemoryMB((long)4);
        vmConfig.setNumCPUs(1);
        vmConfig.setGuestId(VirtualMachineGuestOsIdentifier.OTHER_GUEST.value());
        VirtualMachineFileInfo fileInfo = new VirtualMachineFileInfo();
        fileInfo.setVmPathName(dsMo.getDatastoreRootPath());
        vmConfig.setFiles(fileInfo);

        VirtualLsiLogicController scsiController = new VirtualLsiLogicController();
        scsiController.setSharedBus(VirtualSCSISharing.NO_SHARING);
        scsiController.setBusNumber(0);
        scsiController.setKey(1);
        VirtualDeviceConfigSpec scsiControllerSpec = new VirtualDeviceConfigSpec();
        scsiControllerSpec.setDevice(scsiController);
        scsiControllerSpec.setOperation(VirtualDeviceConfigSpecOperation.ADD);

        vmConfig.getDeviceChange().add(scsiControllerSpec);
        if (hyperHost.createVm(vmConfig)) {
            // Ugly work-around, it takes time for newly created VM to appear
            for (int i = 0; i < 10 && workingVM == null; i++) {
                workingVM = hyperHost.findVmOnHyperHost(vmName);

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    LOGGER.debug("[ignored] interrupted while waiting to config vm.");
                }
            }
        }

        if (workingVM != null) {
            workingVM.tagAsWorkerVM();
        }
        return workingVM;
    }

    public static String resolveHostNameInUrl(DatacenterMO dcMo, String url) {
        LOGGER.info("Resolving host name in url through vCenter, url: " + url);

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            LOGGER.warn("URISyntaxException on url " + url);
            return url;
        }

        String host = uri.getHost();
        if (NetUtils.isValidIp4(host)) {
            LOGGER.info("host name in url is already in IP address, url: " + url);
            return url;
        }

        try {
            ManagedObjectReference morHost = dcMo.findHost(host);
            if (morHost != null) {
                HostMO hostMo = new HostMO(dcMo.getContext(), morHost);
                String managementPortGroupName;
                if (hostMo.getHostType() == VmwareHostType.ESXi)
                    managementPortGroupName = (String)dcMo.getContext().getStockObject("manageportgroup");
                else
                    managementPortGroupName = (String)dcMo.getContext().getStockObject("serviceconsole");

                VmwareHypervisorHostNetworkSummary summary = hostMo.getHyperHostNetworkSummary(managementPortGroupName);
                if (summary == null) {
                    LOGGER.warn("Unable to resolve host name in url through vSphere, url: " + url);
                    return url;
                }

                String hostIp = summary.getHostIp();

                try {
                    URI resolvedUri = new URI(uri.getScheme(), uri.getUserInfo(), hostIp, uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());

                    LOGGER.info("url " + url + " is resolved to " + resolvedUri.toString() + " through vCenter");
                    return resolvedUri.toString();
                } catch (URISyntaxException e) {
                    assert (false);
                    return url;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Unexpected exception ", e);
        }

        return url;
    }

    /**
     * removes the NetworkSection element from the {ovfString} if it is an ovf xml file
     * @param ovfString input string
     * @return like the input string but if xml elements by name {NetworkSection} removed
     */
    public static String removeOVFNetwork(final String ovfString)  {
        if (ovfString == null || ovfString.isEmpty()) {
            return ovfString;
        }
        try {
            final DocumentBuilderFactory factory = ParserUtils.getSaferDocumentBuilderFactory();
            final Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(ovfString.getBytes()));
            final DocumentTraversal traversal = (DocumentTraversal) doc;
            final NodeIterator iterator = traversal.createNodeIterator(doc.getDocumentElement(), NodeFilter.SHOW_ELEMENT, null, true);
            for (Node n = iterator.nextNode(); n != null; n = iterator.nextNode()) {
                final Element e = (Element) n;
                if ("NetworkSection".equals(e.getTagName())) {
                    if (e.getParentNode() != null) {
                        e.getParentNode().removeChild(e);
                    }
                } else if ("rasd:Connection".equals(e.getTagName())) {
                    if (e.getParentNode() != null && e.getParentNode().getParentNode() != null) {
                        e.getParentNode().getParentNode().removeChild(e.getParentNode());
                    }
                }
            }
            final DOMSource domSource = new DOMSource(doc);
            final StringWriter writer = new StringWriter();
            final StreamResult result = new StreamResult(writer);
            final TransformerFactory tf = ParserUtils.getSaferTransformerFactory();
            final Transformer transformer = tf.newTransformer();
            transformer.transform(domSource, result);
            return writer.toString();
        } catch (SAXException | IOException | ParserConfigurationException | TransformerException e) {
            LOGGER.warn("Unexpected exception caught while removing network elements from OVF:", e);
        }
        return ovfString;
    }

    /**
     * deploys a new VM from a ovf spec. It ignores network, defaults locale to 'US'
     * @throws Exception should be a VmwareResourceException
     */
    public static void importVmFromOVF(VmwareHypervisorHost host, String ovfFilePath, String vmName, DatastoreMO dsMo, String diskOption, ManagedObjectReference morRp,
                                       ManagedObjectReference morHost, String configurationId) throws CloudRuntimeException, IOException {

        assert (morRp != null);

        OvfCreateImportSpecParams importSpecParams = new OvfCreateImportSpecParams();
        importSpecParams.setHostSystem(morHost);
        importSpecParams.setLocale("US");
        importSpecParams.setEntityName(vmName);
        String deploymentOption = StringUtils.isNotBlank(configurationId) ? configurationId : "";
        importSpecParams.setDeploymentOption(deploymentOption);
        importSpecParams.setDiskProvisioning(diskOption); // diskOption: thin, thick, etc

        String ovfDescriptor = removeOVFNetwork(HttpNfcLeaseMO.readOvfContent(ovfFilePath));
        VmwareContext context = host.getContext();
        OvfCreateImportSpecResult ovfImportResult = null;
        try {
            ovfImportResult = context.getService().createImportSpec(context.getServiceContent().getOvfManager(), ovfDescriptor, morRp, dsMo.getMor(), importSpecParams);
        } catch (ConcurrentAccessFaultMsg
                | FileFaultFaultMsg
                | InvalidDatastoreFaultMsg
                | InvalidStateFaultMsg
                | RuntimeFaultFaultMsg
                | TaskInProgressFaultMsg
                | VmConfigFaultFaultMsg error) {
            throw new CloudRuntimeException("ImportSpec creation failed", error);
        }
        if (ovfImportResult == null) {
            String msg = "createImportSpec() failed. ovfFilePath: " + ovfFilePath + ", vmName: " + vmName + ", diskOption: " + diskOption;
            LOGGER.error(msg);
            throw new CloudRuntimeException(msg);
        }
        if(!ovfImportResult.getError().isEmpty()) {
            for (LocalizedMethodFault fault : ovfImportResult.getError()) {
                LOGGER.error("createImportSpec error: " + fault.getLocalizedMessage());
            }
            throw new CloudRuntimeException("Failed to create an import spec from " + ovfFilePath + ". Check log for details.");
        }

        if (!ovfImportResult.getWarning().isEmpty()) {
            for (LocalizedMethodFault fault : ovfImportResult.getError()) {
                LOGGER.warn("createImportSpec warning: " + fault.getLocalizedMessage());
            }
        }

        DatacenterMO dcMo = null;
        try {
            dcMo = new DatacenterMO(context, host.getHyperHostDatacenter());
        } catch (Exception e) {
            throw new CloudRuntimeException(String.format("no datacenter for host '%s' available in context", context.getServerAddress()), e);
        }
        ManagedObjectReference folderMO = null;
        try {
            folderMO = dcMo.getVmFolder();
        } catch (Exception e) {
            throw new CloudRuntimeException("no management handle for VmFolder", e);
        }
        ManagedObjectReference morLease = null;
        try {
            morLease = context.getService().importVApp(morRp, ovfImportResult.getImportSpec(), folderMO, morHost);
        } catch (DuplicateNameFaultMsg
                | FileFaultFaultMsg
                | InsufficientResourcesFaultFaultMsg
                | InvalidDatastoreFaultMsg
                | InvalidNameFaultMsg
                | OutOfBoundsFaultMsg
                | RuntimeFaultFaultMsg
                | VmConfigFaultFaultMsg fault) {
            throw new CloudRuntimeException("import vApp failed",fault);
        }
        if (morLease == null) {
            String msg = "importVApp() failed. ovfFilePath: " + ovfFilePath + ", vmName: " + vmName + ", diskOption: " + diskOption;
            LOGGER.error(msg);
            throw new CloudRuntimeException(msg);
        }
        boolean importSuccess = true;
        final HttpNfcLeaseMO leaseMo = new HttpNfcLeaseMO(context, morLease);
        HttpNfcLeaseState state = null;
        try {
            state = leaseMo.waitState(new HttpNfcLeaseState[] {HttpNfcLeaseState.READY, HttpNfcLeaseState.ERROR});
        } catch (Exception e) {
            throw new CloudRuntimeException("exception while waiting for leaseMO", e);
        }
        try {
            if (state == HttpNfcLeaseState.READY) {
                final long totalBytes = HttpNfcLeaseMO.calcTotalBytes(ovfImportResult);
                File ovfFile = new File(ovfFilePath);

                HttpNfcLeaseInfo httpNfcLeaseInfo = null;
                try {
                    httpNfcLeaseInfo = leaseMo.getLeaseInfo();
                } catch (Exception e) {
                    throw new CloudRuntimeException("error waiting for lease info", e);
                }
                List<HttpNfcLeaseDeviceUrl> deviceUrls = httpNfcLeaseInfo.getDeviceUrl();
                long bytesAlreadyWritten = 0;

                final HttpNfcLeaseMO.ProgressReporter progressReporter = leaseMo.createProgressReporter();
                try {
                    for (HttpNfcLeaseDeviceUrl deviceUrl : deviceUrls) {
                        String deviceKey = deviceUrl.getImportKey();
                        for (OvfFileItem ovfFileItem : ovfImportResult.getFileItem()) {
                            if (deviceKey.equals(ovfFileItem.getDeviceId())) {
                                String absoluteFile = ovfFile.getParent() + File.separator + ovfFileItem.getPath();
                                LOGGER.info("Uploading file: " + absoluteFile);
                                File f = new File(absoluteFile);
                                if (f.exists()){
                                    String urlToPost = deviceUrl.getUrl();
                                    urlToPost = resolveHostNameInUrl(dcMo, urlToPost);
                                    context.uploadVmdkFile(ovfFileItem.isCreate() ? "PUT" : "POST", urlToPost, absoluteFile, bytesAlreadyWritten, new ActionDelegate<Long>() {
                                        @Override
                                        public void action(Long param) {
                                            progressReporter.reportProgress((int)(param * 100 / totalBytes));
                                        }
                                    });
                                    bytesAlreadyWritten += ovfFileItem.getSize();
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    String erroMsg = "File upload task failed to complete due to: " + e.getMessage();
                    LOGGER.error(erroMsg);
                    importSuccess = false; // Set flag to cleanup the stale template left due to failed import operation, if any
                    throw new CloudRuntimeException(erroMsg, e);
                } catch (Throwable th) {
                    String errorMsg = "throwable caught during file upload task: " + th.getMessage();
                    LOGGER.error(errorMsg);
                    importSuccess = false; // Set flag to cleanup the stale template left due to failed import operation, if any
                    throw new CloudRuntimeException(errorMsg, th);
                } finally {
                    progressReporter.close();
                }
                if (bytesAlreadyWritten == totalBytes) {
                    try {
                        leaseMo.updateLeaseProgress(100);
                    } catch (Exception e) {
                        throw new CloudRuntimeException("error while waiting for lease update", e);
                    }
                }
            } else if (state == HttpNfcLeaseState.ERROR) {
                LocalizedMethodFault error = null;
                try {
                    error = leaseMo.getLeaseError();
                } catch (Exception e) {
                    throw new CloudRuntimeException("error getting lease error", e);
                }
                MethodFault fault = error.getFault();
                String erroMsg = "Object creation on vCenter failed due to: Exception: " + fault.getClass().getName() + ", message: " + error.getLocalizedMessage();
                LOGGER.error(erroMsg);
                throw new CloudRuntimeException(erroMsg);
            }
        } finally {
            try {
                if (!importSuccess) {
                    LOGGER.error("Aborting the lease on " + vmName + " after import operation failed.");
                    leaseMo.abortLease();
                } else {
                    leaseMo.completeLease();
                }
            } catch (Exception e) {
                throw new CloudRuntimeException("error completing lease", e);
            }
        }
    }

    public static List<Pair<String, Boolean>> readOVF(VmwareHypervisorHost host, String ovfFilePath, DatastoreMO dsMo) throws Exception {
        List<Pair<String, Boolean>> ovfVolumeInfos = new ArrayList<Pair<String, Boolean>>();
        List<String> files = new ArrayList<String>();

        ManagedObjectReference morRp = host.getHyperHostOwnerResourcePool();
        assert (morRp != null);
        ManagedObjectReference morHost = host.getMor();
        String importEntityName = UUID.randomUUID().toString();
        OvfCreateImportSpecParams importSpecParams = new OvfCreateImportSpecParams();
        importSpecParams.setHostSystem(morHost);
        importSpecParams.setLocale("US");
        importSpecParams.setEntityName(importEntityName);
        importSpecParams.setDeploymentOption("");

        String ovfDescriptor = removeOVFNetwork(HttpNfcLeaseMO.readOvfContent(ovfFilePath));
        VmwareContext context = host.getContext();
        OvfCreateImportSpecResult ovfImportResult = context.getService().createImportSpec(context.getServiceContent().getOvfManager(), ovfDescriptor, morRp, dsMo.getMor(),
                importSpecParams);

        if (ovfImportResult == null) {
            String msg = "createImportSpec() failed. ovfFilePath: " + ovfFilePath;
            LOGGER.error(msg);
            throw new Exception(msg);
        }

        if (!ovfImportResult.getError().isEmpty()) {
            for (LocalizedMethodFault fault : ovfImportResult.getError()) {
                LOGGER.error("createImportSpec error: " + fault.getLocalizedMessage());
            }
            throw new CloudException("Failed to create an import spec from " + ovfFilePath + ". Check log for details.");
        }

        if (!ovfImportResult.getWarning().isEmpty()) {
            for (LocalizedMethodFault fault : ovfImportResult.getError()) {
                LOGGER.warn("createImportSpec warning: " + fault.getLocalizedMessage());
            }
        }

        VirtualMachineImportSpec importSpec = (VirtualMachineImportSpec)ovfImportResult.getImportSpec();
        if (importSpec == null) {
            String msg = "createImportSpec() failed to create import specification for OVF template at " + ovfFilePath;
            LOGGER.error(msg);
            throw new Exception(msg);
        }

        File ovfFile = new File(ovfFilePath);
        for (OvfFileItem ovfFileItem : ovfImportResult.getFileItem()) {
            String absFile = ovfFile.getParent() + File.separator + ovfFileItem.getPath();
            files.add(absFile);
        }


       int osDiskSeqNumber = 0;
       VirtualMachineConfigSpec config = importSpec.getConfigSpec();
       String paramVal = getOVFParamValue(config);
       if (paramVal != null && !paramVal.isEmpty()) {
           try {
               osDiskSeqNumber = getOsDiskFromOvfConf(config, paramVal);
           } catch (Exception e) {
               osDiskSeqNumber = 0;
           }
       }

        int diskCount = 0;
        int deviceCount = 0;
        List<VirtualDeviceConfigSpec> deviceConfigList = config.getDeviceChange();
        for (VirtualDeviceConfigSpec deviceSpec : deviceConfigList) {
            Boolean osDisk = false;
            VirtualDevice device = deviceSpec.getDevice();
            if (device instanceof VirtualDisk) {
                if ((osDiskSeqNumber == 0 && diskCount == 0) || osDiskSeqNumber == deviceCount) {
                    osDisk = true;
                }
                Pair<String, Boolean> ovfVolumeInfo = new Pair<String, Boolean>(files.get(diskCount), osDisk);
                ovfVolumeInfos.add(ovfVolumeInfo);
                diskCount++;
            }
            deviceCount++;
        }
        return ovfVolumeInfos;
    }

    public static void createOvfFile(VmwareHypervisorHost host, String diskFileName, String ovfName, String datastorePath, String templatePath, long diskCapacity, long fileSize,
            ManagedObjectReference morDs) throws Exception {
        VmwareContext context = host.getContext();
        ManagedObjectReference morOvf = context.getServiceContent().getOvfManager();
        VirtualMachineMO workerVmMo = HypervisorHostHelper.createWorkerVM(host, new DatastoreMO(context, morDs), ovfName, null);
        if (workerVmMo == null)
            throw new Exception("Unable to find just-created worker VM");

        String[] disks = {datastorePath + File.separator + diskFileName};
        try {
            VirtualMachineConfigSpec vmConfigSpec = new VirtualMachineConfigSpec();
            VirtualDeviceConfigSpec deviceConfigSpec = new VirtualDeviceConfigSpec();

            // Reconfigure worker VM with datadisk
            VirtualDevice device = VmwareHelper.prepareDiskDevice(workerVmMo, null, -1, disks, morDs, -1, 1, null);
            deviceConfigSpec.setDevice(device);
            deviceConfigSpec.setOperation(VirtualDeviceConfigSpecOperation.ADD);
            vmConfigSpec.getDeviceChange().add(deviceConfigSpec);
            workerVmMo.configureVm(vmConfigSpec);

            // Write OVF descriptor file
            OvfCreateDescriptorParams ovfDescParams = new OvfCreateDescriptorParams();
            String deviceId = File.separator + workerVmMo.getMor().getValue() + File.separator + "VirtualIDEController0:0";
            OvfFile ovfFile = new OvfFile();
            ovfFile.setPath(diskFileName);
            ovfFile.setDeviceId(deviceId);
            ovfFile.setSize(fileSize);
            ovfFile.setCapacity(diskCapacity);
            ovfDescParams.getOvfFiles().add(ovfFile);
            OvfCreateDescriptorResult ovfCreateDescriptorResult = context.getService().createDescriptor(morOvf, workerVmMo.getMor(), ovfDescParams);

            String ovfPath = templatePath + File.separator + ovfName + ".ovf";
            try {
                FileWriter out = new FileWriter(ovfPath);
                out.write(ovfCreateDescriptorResult.getOvfDescriptor());
                out.close();
            } catch (Exception e) {
                throw e;
            }
        } finally {
            workerVmMo.detachAllDisksAndDestroy();
        }
    }

    public static int getOsDiskFromOvfConf(VirtualMachineConfigSpec config, String deviceLocation) {
        List<VirtualDeviceConfigSpec> deviceConfigList = config.getDeviceChange();
        int controllerKey = 0;
        int deviceSeqNumber = 0;
        int controllerNumber = 0;
        int deviceNodeNumber = 0;
        int controllerCount = 0;
        String[] virtualNodeInfo = deviceLocation.split(":");

        if (deviceLocation.startsWith("scsi")) {
           controllerNumber = Integer.parseInt(virtualNodeInfo[0].substring(4)); // get substring excluding prefix scsi
           deviceNodeNumber = Integer.parseInt(virtualNodeInfo[1]);

           for (VirtualDeviceConfigSpec deviceConfig : deviceConfigList) {
               VirtualDevice device = deviceConfig.getDevice();
               if (device instanceof VirtualSCSIController) {
                   if (controllerNumber == controllerCount) { //((VirtualSCSIController)device).getBusNumber()) {
                       controllerKey = device.getKey();
                       break;
                   }
                   controllerCount++;
               }
           }
       } else {
           controllerNumber = Integer.parseInt(virtualNodeInfo[0].substring(3)); // get substring excluding prefix ide
           deviceNodeNumber = Integer.parseInt(virtualNodeInfo[1]);
           controllerCount = 0;

           for (VirtualDeviceConfigSpec deviceConfig : deviceConfigList) {
               VirtualDevice device = deviceConfig.getDevice();
               if (device instanceof VirtualIDEController) {
                   if (controllerNumber == controllerCount) { //((VirtualIDEController)device).getBusNumber()) {
                       // Only 2 IDE controllers supported and they will have bus numbers 0 and 1
                       controllerKey = device.getKey();
                       break;
                   }
                   controllerCount++;
               }
           }
       }
       // Get devices on this controller at specific device node.
       for (VirtualDeviceConfigSpec deviceConfig : deviceConfigList) {
           VirtualDevice device = deviceConfig.getDevice();
           if (device instanceof VirtualDisk) {
               if (controllerKey == device.getControllerKey() && deviceNodeNumber == device.getUnitNumber()) {
                   break;
               }
               deviceSeqNumber++;
           }
       }
       return deviceSeqNumber;
   }

   public static String getOVFParamValue(VirtualMachineConfigSpec config) {
       String paramVal = "";
       List<OptionValue> options = config.getExtraConfig();
       for (OptionValue option : options) {
           if (OVA_OPTION_KEY_BOOTDISK.equalsIgnoreCase(option.getKey())) {
               paramVal = (String)option.getValue();
               break;
           }
       }
       return paramVal;
   }

    public static ManagedObjectReference getHypervisorHostMorFromGuid(String guid) {
        if (guid == null) {
            return null;
        }

        String[] tokens = guid.split("@");
        if (tokens == null || tokens.length != 2) {
            LOGGER.error("Invalid content in host guid");
            return null;
        }

        String[] hostTokens = tokens[0].split(":");
        if (hostTokens == null || hostTokens.length != 2) {
            LOGGER.error("Invalid content in host guid");
            return null;
        }

        ManagedObjectReference morHyperHost = new ManagedObjectReference();
        morHyperHost.setType(hostTokens[0]);
        morHyperHost.setValue(hostTokens[1]);

        return morHyperHost;
    }

    public static String getScsiController(Pair<String, String> controllerInfo) {
        String rootDiskController = controllerInfo.first();
        String dataDiskController = controllerInfo.second();

        String scsiDiskController; //If any of the controller provided is SCSI then return it's sub-type.
        if (isIdeController(rootDiskController) && isIdeController(dataDiskController)) {
            //Default controllers would exist
            return null;
        } else if (isIdeController(rootDiskController) || isIdeController(dataDiskController)) {
            // Only one of the controller types is IDE. Pick the other controller type to create controller.
            if (isIdeController(rootDiskController)) {
                scsiDiskController = dataDiskController;
            } else {
                scsiDiskController = rootDiskController;
            }
        } else if (DiskControllerType.getType(rootDiskController) != DiskControllerType.getType(dataDiskController)) {
            // Both ROOT and DATA controllers are SCSI controllers but different sub-types, then prefer ROOT controller
            scsiDiskController = rootDiskController;
        } else {
            // Both are SCSI controllers.
            scsiDiskController = rootDiskController;
        }
        return scsiDiskController;
    }

    public static boolean isIdeController(String controller) {
        return DiskControllerType.getType(controller) == DiskControllerType.ide;
    }

    public static void createBaseFolder(DatastoreMO dsMo, VmwareHypervisorHost hyperHost, StoragePoolType poolType) throws Exception {
        if (poolType != null && poolType == StoragePoolType.DatastoreCluster) {
            StoragepodMO storagepodMO = new StoragepodMO(hyperHost.getContext(), dsMo.getMor());
            List<ManagedObjectReference> datastoresInCluster = storagepodMO.getDatastoresInDatastoreCluster();
            for (ManagedObjectReference datastore : datastoresInCluster) {
                DatastoreMO childDsMo = new DatastoreMO(hyperHost.getContext(), datastore);
                createBaseFolderInDatastore(childDsMo, hyperHost.getHyperHostDatacenter());
            }
        } else {
            createBaseFolderInDatastore(dsMo, hyperHost.getHyperHostDatacenter());
        }
    }

    public static void createBaseFolderInDatastore(DatastoreMO dsMo, ManagedObjectReference mor) throws Exception {
        String dsPath = String.format("[%s]", dsMo.getName());
        String folderPath = String.format("[%s] %s", dsMo.getName(), VSPHERE_DATASTORE_BASE_FOLDER);
        String hiddenFolderPath = String.format("%s/%s", folderPath, VSPHERE_DATASTORE_HIDDEN_FOLDER);

        if (!dsMo.folderExists(dsPath, VSPHERE_DATASTORE_BASE_FOLDER)) {
            LOGGER.info(String.format("vSphere datastore base folder [%s] does not exist on datastore [%s]. We will create it.", VSPHERE_DATASTORE_BASE_FOLDER, dsMo.getName()));
            dsMo.makeDirectory(folderPath, mor);
            // Adding another directory so vCentre doesn't remove the fcd directory when it's empty
            dsMo.makeDirectory(hiddenFolderPath, mor);
        }
    }

    public static Integer getHostHardwareVersion(VmwareHypervisorHost host) {
        Integer version = null;
        HostMO hostMo = new HostMO(host.getContext(), host.getMor());
        String hostApiVersion = "";
        try {
            hostApiVersion = hostMo.getHostAboutInfo().getApiVersion();
        } catch (Exception ignored) {}
        if (hostApiVersion == null) {
            hostApiVersion = "";
        }
        version = apiVersionHardwareVersionMap.get(hostApiVersion);
        return version;
    }

    /*
      Finds minimum host hardware version as String, of two hosts when both of them are not null
      and hardware version of both hosts is different.
      Return null otherwise
     */
    public static String getMinimumHostHardwareVersion(VmwareHypervisorHost host1, VmwareHypervisorHost host2) {
        String hardwareVersion = null;
        if (host1 != null & host2 != null) {
            Integer host1Version = getHostHardwareVersion(host1);
            Integer host2Version = getHostHardwareVersion(host2);
            if (host1Version != null && host2Version != null && !host1Version.equals(host2Version)) {
                hardwareVersion = VirtualMachineMO.getVmxFormattedVirtualHardwareVersion(Math.min(host1Version, host2Version));
            }
        }
        return hardwareVersion;
    }

    public static VirtualMachineMO findVmOnHypervisorHostOrPeer(VmwareHypervisorHost hypervisorHost, String vmName) throws Exception {
        VirtualMachineMO vmMo = hypervisorHost.findVmOnHyperHost(vmName);
        if (vmMo == null) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(String.format("Unable to find the VM on host %s, try within datacenter", hypervisorHost.getHyperHostName()));
            }
            vmMo = hypervisorHost.findVmOnPeerHyperHost(vmName);
        }
        return vmMo;
    }
}
