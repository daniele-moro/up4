package org.omecproject.up4.behavior;

import org.omecproject.up4.ForwardingActionRule;
import org.omecproject.up4.PacketDetectionRule;
import org.omecproject.up4.PdrStats;
import org.omecproject.up4.Up4Translator;
import org.omecproject.up4.UpfFlow;
import org.omecproject.up4.UpfInterface;
import org.omecproject.up4.UpfProgrammable;
import org.omecproject.up4.impl.SouthConstants;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.Ip4Prefix;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.criteria.PiCriterion;
import org.onosproject.net.pi.model.PiPipeconf;
import org.onosproject.net.pi.model.PiTableId;
import org.onosproject.net.pi.runtime.PiCounterCell;
import org.onosproject.net.pi.runtime.PiCounterCellHandle;
import org.onosproject.net.pi.runtime.PiCounterCellId;
import org.onosproject.net.pi.service.PiPipeconfService;
import org.onosproject.p4runtime.api.P4RuntimeClient;
import org.onosproject.p4runtime.api.P4RuntimeController;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.onosproject.net.pi.model.PiCounterType.INDIRECT;

/**
 * Implementation of a UPF programmable device behavior.
 * TODO: this needs to be moved to
 * onos/pipelines/fabric/impl/src/main/java/org/onosproject/pipelines/fabric/impl/behaviour/up4/
 * and referenced as upfProgrammable = deviceService.getDevice(deviceId).as(UpfProgrammable.class);
 */
@Component(immediate = true,
        service = {UpfProgrammable.class})
public class FabricUpfProgrammable implements UpfProgrammable {

    private static final int DEFAULT_PRIORITY = 128;
    private static final long DEFAULT_P4_DEVICE_ID = 1;
    private final Logger log = LoggerFactory.getLogger(getClass());
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected P4RuntimeController controller;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PiPipeconfService piPipeconfService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private Up4Translator up4Translator;
    DeviceId deviceId;
    private ApplicationId appId;

    @Activate
    protected void activate() {
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        log.info("Stopped");
    }

    @Override
    public boolean init(ApplicationId appId, DeviceId deviceId) {
        this.appId = appId;
        this.deviceId = deviceId;
        log.info("UpfProgrammable initialized for appId {} and deviceId {}", appId, deviceId);
        return true;
    }

    @Override
    public void cleanUp(ApplicationId appId) {
        log.info("Clearing all UPF-related table entries.");
        flowRuleService.removeFlowRulesById(appId);
        up4Translator.reset();
    }

    @Override
    public void clearInterfaces() {
        log.info("Clearing all UPF interfaces.");
        for (FlowRule entry : flowRuleService.getFlowEntriesById(appId)) {
            if (up4Translator.isFabricInterface(entry)) {
                flowRuleService.removeFlowRules(entry);
            }
        }
    }

    @Override
    public void clearFlows() {
        log.info("Clearing all UE sessions.");
        int pdrsCleared = 0;
        int farsCleared = 0;
        for (FlowRule entry : flowRuleService.getFlowEntriesById(appId)) {
            if (up4Translator.isFabricPdr(entry)) {
                pdrsCleared++;
                flowRuleService.removeFlowRules(entry);
            } else if (up4Translator.isFabricFar(entry)) {
                farsCleared++;
                flowRuleService.removeFlowRules(entry);
            }
        }
        log.info("Cleared {} PDRs and {} FARS.", pdrsCleared, farsCleared);
    }

    @Override
    public DeviceId deviceId() {
        return this.deviceId;
    }


    @Override
    public PdrStats readCounter(int cellId) {
        PdrStats.Builder stats = PdrStats.builder().withCellId(cellId);

        // Get client and pipeconf.
        P4RuntimeClient client = controller.get(deviceId);
        if (client == null) {
            log.warn("Unable to find client for {}, aborting operation", deviceId);
            return stats.build();
        }
        Optional<PiPipeconf> optPipeconf = piPipeconfService.getPipeconf(deviceId);
        if (optPipeconf.isEmpty()) {
            log.warn("Unable to load piPipeconf for {}, aborting operation", deviceId);
            return stats.build();
        }
        PiPipeconf pipeconf = optPipeconf.get();


        // Make list of cell handles we want to read.
        List<PiCounterCellHandle> counterCellHandles = List.of(
                PiCounterCellHandle.of(deviceId,
                        PiCounterCellId.ofIndirect(SouthConstants.INGRESS_COUNTER_ID, cellId)),
                PiCounterCellHandle.of(deviceId,
                        PiCounterCellId.ofIndirect(SouthConstants.EGRESS_COUNTER_ID, cellId)));

        // Query the device.
        Collection<PiCounterCell> counterEntryResponse = client.read(
                DEFAULT_P4_DEVICE_ID, pipeconf)
                .handles(counterCellHandles).submitSync()
                .all(PiCounterCell.class);

        // Process response.
        counterEntryResponse.forEach(counterCell -> {
            if (counterCell.cellId().counterType() != INDIRECT) {
                log.warn("Invalid counter data type {}, skipping", counterCell.cellId().counterType());
                return;
            }
            if (cellId != counterCell.cellId().index()) {
                log.warn("Unrecognized counter index {}, skipping", counterCell);
                return;
            }
            if (counterCell.cellId().counterId().equals(SouthConstants.INGRESS_COUNTER_ID)) {
                stats.setIngress(counterCell.data().packets(), counterCell.data().bytes());
            } else if (counterCell.cellId().counterId().equals(SouthConstants.EGRESS_COUNTER_ID)) {
                stats.setEgress(counterCell.data().packets(), counterCell.data().bytes());
            } else {
                log.warn("Unrecognized counter ID {}, skipping", counterCell);
            }
        });
        return stats.build();
    }


    @Override
    public void addPdr(PacketDetectionRule pdr) {
        try {
            FlowRule fabricPdr = up4Translator.pdrToFabricEntry(pdr, deviceId, appId, DEFAULT_PRIORITY);
            log.info("Installing {}", pdr.toString());
            flowRuleService.applyFlowRules(fabricPdr);
            log.debug("FAR added with flowID {}", fabricPdr.id().value());
        } catch (Up4Translator.Up4TranslationException e) {
            log.warn("Unable to insert malformed FAR to dataplane! {}", pdr.toString());
        }
    }


    @Override
    public void addFar(ForwardingActionRule far) {
        try {
            FlowRule fabricFar = up4Translator.farToFabricEntry(far, deviceId, appId, DEFAULT_PRIORITY);
            log.info("Installing {}", far.toString());
            flowRuleService.applyFlowRules(fabricFar);
            log.debug("FAR added with flowID {}", fabricFar.id().value());
        } catch (Up4Translator.Up4TranslationException e) {
            log.warn("Unable to insert FAR {} to dataplane! Error was: {}", far, e.getMessage());
        }
    }

    @Override
    public void addInterface(UpfInterface upfInterface) {
        try {
            FlowRule flowRule = up4Translator.interfaceToFabricEntry(upfInterface, deviceId, appId, DEFAULT_PRIORITY);
            log.info("Installing {}", upfInterface);
            flowRuleService.applyFlowRules(flowRule);
            log.debug("Interface added with flowID {}", flowRule.id().value());
        } catch (Up4Translator.Up4TranslationException e) {
            log.warn("Unable to install interface {} on dataplane! Error was: {}", upfInterface, e.getMessage());
        }
    }

    @Override
    public void addS1uInterface(Ip4Address s1uAddr) {
        addInterface(UpfInterface.createS1uFrom(s1uAddr));
    }

    @Override
    public void addUePool(Ip4Prefix poolPrefix) {
        addInterface(UpfInterface.createUePoolFrom(poolPrefix));
    }


    private boolean removeEntry(PiCriterion match, PiTableId tableId, boolean failSilent) {
        FlowRule entry = DefaultFlowRule.builder()
                .forDevice(deviceId).fromApp(appId).makePermanent()
                .forTable(tableId)
                .withSelector(DefaultTrafficSelector.builder().matchPi(match).build())
                .withPriority(DEFAULT_PRIORITY)
                .build();

        /*
         *  FIXME: Stupid stupid slow hack, needed because removeFlowRules expects FlowRule objects
         *   with correct and complete actions and parameters, but P4Runtime deletion requests
         *   will not have those.
         */
        for (FlowEntry installedEntry : flowRuleService.getFlowEntriesById(appId)) {
            if (installedEntry.selector().equals(entry.selector())) {
                log.info("Found matching entry to remove, it has FlowID {}", installedEntry.id());
                flowRuleService.removeFlowRules(installedEntry);
                return true;
            }
        }
        if (!failSilent) {
            log.error("Did not find a flow rule with the given match conditions! Deleting nothing.");
        }
        return false;
    }

    public Collection<UpfFlow> getFlows() {
        Map<Integer, UpfFlow.Builder> globalFarToSessionBuilder = new HashMap<>();
        List<ForwardingActionRule> fars = new ArrayList<>();
        for (FlowRule flowRule : flowRuleService.getFlowEntriesById(appId)) {
            if (up4Translator.isFabricFar(flowRule)) {
                // If its a far, save it for later
                try {
                    fars.add(up4Translator.fabricEntryToFar(flowRule));
                } catch (Up4Translator.Up4TranslationException e) {
                    log.warn("Found what appears to be a FAR but we can't translate it?? {}", flowRule);
                }
            } else if (up4Translator.isFabricPdr(flowRule)) {
                // If its a PDR, create a flow builder for it
                try {
                    PacketDetectionRule pdr = up4Translator.fabricEntryToPdr(flowRule);
                    globalFarToSessionBuilder.put(pdr.getGlobalFarId(),
                            UpfFlow.builder().setPdr(pdr).addStats(readCounter(pdr.counterId())));
                } catch (Up4Translator.Up4TranslationException e) {
                    log.warn("Found what appears to be a PDR but we can't translate it?? {}", flowRule);
                }
            }
        }
        for (ForwardingActionRule far : fars) {
            var builder = globalFarToSessionBuilder.getOrDefault(far.getGlobalFarId(), null);
            if (builder != null) {
                builder.addFar(far);
            } else {
                log.warn("Found a FAR with no corresponding PDR: {}", far);
            }
        }
        List<UpfFlow> results = new ArrayList<>();
        for (var builder : globalFarToSessionBuilder.values()) {
            results.add(builder.build());
        }
        return results;
    }

    @Override
    public Collection<PacketDetectionRule> getInstalledPdrs() {
        ArrayList<PacketDetectionRule> pdrs = new ArrayList<>();
        for (FlowRule flowRule : flowRuleService.getFlowEntriesById(appId)) {
            if (up4Translator.isFabricPdr(flowRule)) {
                try {
                    pdrs.add(up4Translator.fabricEntryToPdr(flowRule));
                } catch (Up4Translator.Up4TranslationException e) {
                    log.warn("Found what appears to be a PDR but we can't translate it?? {}", flowRule);
                }
            }
        }
        return pdrs;
    }

    @Override
    public Collection<ForwardingActionRule> getInstalledFars() {
        ArrayList<ForwardingActionRule> fars = new ArrayList<>();
        for (FlowRule flowRule : flowRuleService.getFlowEntriesById(appId)) {
            if (up4Translator.isFabricFar(flowRule)) {
                try {
                    fars.add(up4Translator.fabricEntryToFar(flowRule));
                } catch (Up4Translator.Up4TranslationException e) {
                    log.warn("Found what appears to be a FAR but we can't translate it?? {}", flowRule);
                }
            }
        }
        return fars;
    }

    public Collection<UpfInterface> getInstalledInterfaces() {
        ArrayList<UpfInterface> ifaces = new ArrayList<>();
        for (FlowRule flowRule : flowRuleService.getFlowEntriesById(appId)) {
            if (up4Translator.isFabricInterface(flowRule)) {
                try {
                    ifaces.add(up4Translator.fabricEntryToInterface(flowRule));
                } catch (Up4Translator.Up4TranslationException e) {
                    log.warn("Found what appears to be a interface table entry but we can't translate it?? {}",
                            flowRule);
                }
            }
        }
        return ifaces;
    }


    @Override
    public void removePdr(PacketDetectionRule pdr) {
        PiCriterion match;
        PiTableId tableId;
        if (pdr.isUplink()) {
            match = PiCriterion.builder()
                    .matchExact(SouthConstants.UE_ADDR_KEY, pdr.ueAddress().toInt())
                    .matchExact(SouthConstants.TEID_KEY, pdr.teid().asArray())
                    .matchExact(SouthConstants.TUNNEL_DST_KEY, pdr.tunnelDest().toInt())
                    .build();
            tableId = SouthConstants.PDR_UPLINK_TBL;
        } else if (pdr.isDownlink()) {
            match = PiCriterion.builder()
                    .matchExact(SouthConstants.UE_ADDR_KEY, pdr.ueAddress().toInt())
                    .build();
            tableId = SouthConstants.PDR_DOWNLINK_TBL;
        } else {
            log.error("Removal of flexible PDRs not yet supported.");
            return;
        }
        log.info("Removing {}", pdr.toString());
        removeEntry(match, tableId, false);
    }

    @Override
    public void removeFar(ForwardingActionRule far) {
        log.info("Removing {}", far.toString());

        PiCriterion match = PiCriterion.builder()
                .matchExact(SouthConstants.FAR_ID_KEY, far.getGlobalFarId())
                .build();

        removeEntry(match, SouthConstants.FAR_TBL, false);
    }

    @Override
    public void removeUePool(Ip4Prefix poolPrefix) {
        log.info("Removing S1U interface table entry");
        PiCriterion match = PiCriterion.builder()
                .matchLpm(SouthConstants.IPV4_DST_ADDR, poolPrefix.address().toInt(), poolPrefix.prefixLength())
                .matchExact(SouthConstants.GTPU_IS_VALID, 0)
                .build();
        removeEntry(match, SouthConstants.INTERFACE_LOOKUP, false);
    }

    @Override
    public void removeS1uInterface(Ip4Address s1uAddr) {
        log.info("Removing S1U interface table entry");
        PiCriterion match = PiCriterion.builder()
                .matchLpm(SouthConstants.IPV4_DST_ADDR, s1uAddr.toInt(), 32)
                .matchExact(SouthConstants.GTPU_IS_VALID, 1)
                .build();
        removeEntry(match, SouthConstants.INTERFACE_LOOKUP, false);
    }

    @Override
    public void removeUnknownInterface(Ip4Prefix ifacePrefix) {
        // For when you don't know if its a uePool or s1uInterface table entry
        // Try removing an S1U entry
        PiCriterion match1 = PiCriterion.builder()
                .matchLpm(SouthConstants.IPV4_DST_ADDR, ifacePrefix.address().toInt(), 32)
                .matchExact(SouthConstants.GTPU_IS_VALID, 1)
                .build();
        if (removeEntry(match1, SouthConstants.INTERFACE_LOOKUP, true)) {
            return;
        }
        // If that didn't work, try removing a UE pool entry
        PiCriterion match2 = PiCriterion.builder()
                .matchLpm(SouthConstants.IPV4_DST_ADDR, ifacePrefix.address().toInt(), ifacePrefix.prefixLength())
                .matchExact(SouthConstants.GTPU_IS_VALID, 0)
                .build();
        if (!removeEntry(match2, SouthConstants.INTERFACE_LOOKUP, true)) {
            log.error("Could not remove interface! No matching entry found!");
        }
    }


}