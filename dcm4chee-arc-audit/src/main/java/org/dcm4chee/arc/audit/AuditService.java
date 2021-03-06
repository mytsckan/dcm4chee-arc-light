/*
 * *** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * J4Care.
 * Portions created by the Initial Developer are Copyright (C) 2017
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * *** END LICENSE BLOCK *****
 */

package org.dcm4chee.arc.audit;

import org.dcm4che3.audit.*;
import org.dcm4che3.conf.api.ConfigurationException;
import org.dcm4che3.conf.api.hl7.IHL7ApplicationCache;
import org.dcm4che3.data.*;
import org.dcm4che3.hl7.HL7Message;
import org.dcm4che3.hl7.HL7Segment;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.*;
import org.dcm4che3.net.audit.AuditLogger;
import org.dcm4che3.net.audit.AuditLoggerDeviceExtension;
import org.dcm4che3.net.hl7.HL7Application;
import org.dcm4che3.net.hl7.HL7DeviceExtension;
import org.dcm4che3.net.hl7.UnparsedHL7Message;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4che3.util.ReverseDNS;
import org.dcm4che3.util.StringUtils;
import org.dcm4chee.arc.AssociationEvent;
import org.dcm4chee.arc.HL7ConnectionEvent;
import org.dcm4chee.arc.conf.*;
import org.dcm4chee.arc.event.ArchiveServiceEvent;
import org.dcm4chee.arc.ConnectionEvent;
import org.dcm4chee.arc.event.BulkQueueMessageEvent;
import org.dcm4chee.arc.event.QueueMessageEvent;
import org.dcm4chee.arc.event.SoftwareConfiguration;
import org.dcm4chee.arc.hl7.ArchiveHL7Message;
import org.dcm4chee.arc.keycloak.KeycloakContext;
import org.dcm4chee.arc.delete.StudyDeleteContext;
import org.dcm4chee.arc.retrieve.ExternalRetrieveContext;
import org.dcm4chee.arc.event.RejectionNoteSent;
import org.dcm4chee.arc.exporter.ExportContext;
import org.dcm4chee.arc.patient.PatientMgtContext;
import org.dcm4chee.arc.procedure.ProcedureContext;
import org.dcm4chee.arc.query.QueryContext;
import org.dcm4chee.arc.store.InstanceLocations;
import org.dcm4chee.arc.retrieve.RetrieveContext;
import org.dcm4chee.arc.stgcmt.StgCmtContext;
import org.dcm4chee.arc.store.StoreContext;
import org.dcm4chee.arc.store.StoreSession;
import org.dcm4chee.arc.study.StudyMgtContext;
import org.dcm4chee.arc.qmgt.HttpServletRequestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Vrinda Nayak <vrinda.nayak@j4care.com>
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @since Feb 2016
 */
@ApplicationScoped
public class AuditService {
    private final static Logger LOG = LoggerFactory.getLogger(AuditService.class);
    private final String studyDate = "StudyDate";

    @Inject
    private Device device;

    @Inject
    private IHL7ApplicationCache hl7AppCache;

    private void aggregateAuditMessage(AuditLogger auditLogger, Path path) {
        try {
            AuditServiceUtils.EventType eventType = AuditServiceUtils.EventType.fromFile(path);
            if (path.toFile().length() == 0) {
                LOG.warn("Attempt to read from an empty file.", eventType, path);
                return;
            }
            switch (eventType.eventClass) {
                case APPLN_ACTIVITY:
                    auditApplicationActivity(auditLogger, path, eventType);
                    break;
                case CONN_FAILURE:
                    auditConnectionFailure(auditLogger, path, eventType);
                    break;
                case STORE_WADOR:
                    auditStoreOrWADORetrieve(auditLogger, path, eventType);
                    break;
                case RETRIEVE:
                    auditRetrieve(auditLogger, path, eventType);
                    break;
                case USER_DELETED:
                case SCHEDULER_DELETED:
                    auditDeletion(auditLogger, path, eventType);
                    break;
                case QUERY:
                    auditQuery(auditLogger, path, eventType);
                    break;
                case HL7:
                    auditPatientRecord(auditLogger, path, eventType);
                    break;
                case PROC_STUDY:
                    auditProcedureRecord(auditLogger, path, eventType);
                    break;
                case PROV_REGISTER:
                    auditProvideAndRegister(auditLogger, path, eventType);
                    break;
                case STGCMT:
                    auditStorageCommit(auditLogger, path, eventType);
                    break;
                case INST_RETRIEVED:
                    auditExternalRetrieve(auditLogger, path, eventType);
                    break;
                case LDAP_CHANGES:
                    auditSoftwareConfiguration(auditLogger, path, eventType);
                    break;
                case QUEUE_EVENT:
                    auditQueueMessageEvent(auditLogger, path, eventType);
                    break;
                case IMPAX:
                    auditPatientMismatch(auditLogger, path, eventType);
                    break;
                case ASSOCIATION_FAILURE:
                    auditAssociationFailure(auditLogger, path, eventType);
                    break;
            }
        } catch (Exception e) {
            LOG.warn("Failed in audit : " + e);
        }
    }

    void spoolApplicationActivity(ArchiveServiceEvent event) {
        try {
            if (event.getType() == ArchiveServiceEvent.Type.RELOADED)
                return;

            HttpServletRequest req = event.getRequest();
            AuditInfoBuilder info = req != null
                    ? restfulTriggeredApplicationActivityInfo(req)
                    : systemTriggeredApplicationActivityInfo();
            writeSpoolFile(AuditServiceUtils.EventType.forApplicationActivity(event), info);
        } catch (Exception e) {
            LOG.warn("Failed to spool Application Activity : " + e);
        }
    }

    private AuditInfoBuilder systemTriggeredApplicationActivityInfo() {
        return new AuditInfoBuilder.Builder().calledUserID(device.getDeviceName()).build();
    }

    private AuditInfoBuilder restfulTriggeredApplicationActivityInfo(HttpServletRequest req) {
        return new AuditInfoBuilder.Builder()
                .calledUserID(req.getRequestURI())
                .callingUserID(KeycloakContext.valueOf(req).getUserName())
                .callingHost(req.getRemoteAddr())
                .build();
    }

    private void auditApplicationActivity(AuditLogger auditLogger, Path path, AuditServiceUtils.EventType eventType) {
        SpoolFileReader reader = new SpoolFileReader(path);
        EventIdentificationBuilder ei = toBuildEventIdentification(eventType, null, getEventTime(path, auditLogger));
        AuditInfo archiveInfo = new AuditInfo(reader.getMainInfo());
        ActiveParticipantBuilder[] activeParticipantBuilder = buildApplicationActivityActiveParticipants(auditLogger, eventType, archiveInfo);
        emitAuditMessage(auditLogger, ei, activeParticipantBuilder);
    }

    private ActiveParticipantBuilder[] buildApplicationActivityActiveParticipants(
            AuditLogger auditLogger, AuditServiceUtils.EventType eventType, AuditInfo archiveInfo) {
        ActiveParticipantBuilder[] activeParticipantBuilder = new ActiveParticipantBuilder[2];
        String archiveUserID = archiveInfo.getField(AuditInfo.CALLED_USERID);
        activeParticipantBuilder[0] = new ActiveParticipantBuilder.Builder(
                                archiveUserID,
                                getLocalHostName(auditLogger))
                                .userIDTypeCode(archiveUserIDTypeCode(archiveUserID))
                                .altUserID(AuditLogger.processID())
                                .roleIDCode(eventType.destination)
                                .build();
        if (isServiceUserTriggered(archiveInfo.getField(AuditInfo.CALLING_USERID))) {
            String userID = archiveInfo.getField(AuditInfo.CALLING_USERID);
            activeParticipantBuilder[1] = new ActiveParticipantBuilder.Builder(
                    userID,
                    archiveInfo.getField(AuditInfo.CALLING_HOST))
                    .userIDTypeCode(AuditMessages.userIDTypeCode(userID))
                    .isRequester()
                    .roleIDCode(eventType.source)
                    .build();
        }
        return activeParticipantBuilder;
    }

    private void spoolInstancesDeleted(StoreContext ctx) {
        try {
            writeSpoolFile(
                    AuditServiceUtils.EventType.forInstancesDeleted(ctx),
                    DeletionAuditService.instancesDeletedAuditInfo(ctx, getArchiveDevice()));
        } catch (Exception e) {
            LOG.warn("Failed to spool Instances Deleted : " + e);
        }
    }

    void spoolStudyDeleted(StudyDeleteContext ctx) {
        try {
            writeSpoolFile(
                    AuditServiceUtils.EventType.forStudyDeleted(ctx),
                    DeletionAuditService.studyDeletedAuditInfo(ctx, getArchiveDevice()));
        } catch (Exception e) {
            LOG.warn("Failed to spool Study Deleted : " + e);
        }
    }

    void spoolExternalRejection(RejectionNoteSent rejectionNoteSent) {
        try {
            writeSpoolFile(
                    AuditServiceUtils.EventType.forExternalRejection(rejectionNoteSent),
                    DeletionAuditService.externalRejectionAuditInfo(rejectionNoteSent, getArchiveDevice()));
        } catch (Exception e) {
            LOG.warn("Failed to spool External Rejection : " + e);
        }
    }

    private void auditDeletion(AuditLogger auditLogger, Path path, AuditServiceUtils.EventType eventType) {
        SpoolFileReader reader = new SpoolFileReader(path);
        AuditInfo auditInfo = new AuditInfo(reader.getMainInfo());

        String outcome = auditInfo.getField(AuditInfo.OUTCOME);
        EventIdentificationBuilder ei = toCustomBuildEventIdentification(eventType, outcome,
                auditInfo.getField(AuditInfo.WARNING), getEventTime(path, auditLogger));

        emitAuditMessage(
                DeletionAuditService.auditMsg(auditLogger, reader, eventType, ei),
                auditLogger);
    }

    void spoolQueueMessageEvent(QueueMessageEvent queueMsgEvent) {
        if (queueMsgEvent.getQueueMsg() == null)
            return;

        try {
            writeSpoolFile(
                    AuditServiceUtils.EventType.forQueueEvent(queueMsgEvent.getOperation()),
                    QueueMessageAuditService.queueMsgAuditInfo(queueMsgEvent));
        } catch (Exception e) {
            LOG.warn("Failed to spool Queue Message Event : " + e);
        }
    }

    void spoolBulkQueueMessageEvent(BulkQueueMessageEvent bulkQueueMsgEvent) {
        try {
            writeSpoolFile(
                    AuditServiceUtils.EventType.forQueueEvent(bulkQueueMsgEvent.getOperation()),
                    QueueMessageAuditService.bulkQueueMsgAuditInfo(bulkQueueMsgEvent));
        } catch (Exception e) {
            LOG.warn("Failed to spool Bulk Queue Message Event : " + e);
        }
    }

    private void auditQueueMessageEvent(AuditLogger auditLogger, Path path, AuditServiceUtils.EventType eventType) {
        SpoolFileReader reader = new SpoolFileReader(path);
        AuditInfo auditInfo = new AuditInfo(reader.getMainInfo());
        Calendar eventTime = getEventTime(path, auditLogger);
        emitAuditMessage(
                QueueMessageAuditService.auditMsg(auditInfo, eventType, auditLogger, eventTime),
                auditLogger);
    }

    void spoolSoftwareConfiguration(SoftwareConfiguration softwareConfiguration) {
        try {
            writeSpoolFile(
                    SoftwareConfigurationAuditService.auditInfo(softwareConfiguration),
                    softwareConfiguration.getLdapDiff().toString());
        } catch (Exception e) {
            LOG.warn("Failed to spool Software Configuration Changes : " + e);
        }
    }

    private void auditSoftwareConfiguration(AuditLogger auditLogger, Path path, AuditServiceUtils.EventType eventType) {
        SpoolFileReader reader = new SpoolFileReader(path);
        Calendar eventTime = getEventTime(path, auditLogger);
        emitAuditMessage(
                SoftwareConfigurationAuditService.auditMsg(auditLogger, reader, eventType, eventTime),
                auditLogger);
    }

    void spoolExternalRetrieve(ExternalRetrieveContext ctx) {
        try {
            String outcome = ctx.getResponse().getString(Tag.ErrorComment) != null
                    ? ctx.getResponse().getString(Tag.ErrorComment) + ctx.failed()
                    : null;
            String warning = ctx.warning() > 0
                    ? "Number Of Warning Sub operations" + ctx.warning()
                    : null;
            AuditInfoBuilder info = new AuditInfoBuilder.Builder()
                    .callingUserID(ctx.getRequesterUserID())
                    .callingHost(ctx.getRequesterHostName())
                    .calledHost(ctx.getRemoteHostName())
                    .calledUserID(ctx.getRemoteAET())
                    .moveUserID(ctx.getRequestURI())
                    .destUserID(ctx.getDestinationAET())
                    .warning(warning)
                    .studyUIDAccNumDate(ctx.getKeys())
                    .outcome(outcome)
                    .build();
            writeSpoolFile(AuditServiceUtils.EventType.INST_RETRV, info);
        } catch (Exception e) {
            LOG.warn("Failed to spool External Retrieve : " + e);
        }
    }

    private void auditExternalRetrieve(AuditLogger auditLogger, Path path, AuditServiceUtils.EventType eventType) {
        SpoolFileReader reader = new SpoolFileReader(path);
        AuditInfo i = new AuditInfo(reader.getMainInfo());
        EventIdentificationBuilder ei = toCustomBuildEventIdentification(eventType, i.getField(AuditInfo.OUTCOME),
                i.getField(AuditInfo.WARNING), getEventTime(path, auditLogger));

        ActiveParticipantBuilder[] activeParticipantBuilder = new ActiveParticipantBuilder[4];
        String userID = i.getField(AuditInfo.CALLING_USERID);
        activeParticipantBuilder[0] = new ActiveParticipantBuilder.Builder(
                                userID,
                                i.getField(AuditInfo.CALLING_HOST))
                                .userIDTypeCode(AuditMessages.userIDTypeCode(userID))
                                .isRequester()
                                .build();
        activeParticipantBuilder[1] = new ActiveParticipantBuilder.Builder(
                                i.getField(AuditInfo.MOVE_USER_ID),
                                getLocalHostName(auditLogger))
                                .userIDTypeCode(AuditMessages.UserIDTypeCode.URI)
                                .altUserID(AuditLogger.processID())
                                .build();
        activeParticipantBuilder[2] = new ActiveParticipantBuilder.Builder(
                                i.getField(AuditInfo.CALLED_USERID),
                                i.getField(AuditInfo.CALLED_HOST))
                                .userIDTypeCode(AuditMessages.UserIDTypeCode.StationAETitle)
                                .roleIDCode(eventType.source)
                                .build();
        activeParticipantBuilder[3] = new ActiveParticipantBuilder.Builder(
                                i.getField(AuditInfo.DEST_USER_ID),
                                i.getField(AuditInfo.DEST_NAP_ID))
                                .userIDTypeCode(AuditMessages.UserIDTypeCode.StationAETitle)
                                .roleIDCode(eventType.destination)
                                .build();
        ParticipantObjectIdentificationBuilder studyPOI = new ParticipantObjectIdentificationBuilder.Builder(
                                                            i.getField(AuditInfo.STUDY_UID),
                                                            AuditMessages.ParticipantObjectIDTypeCode.StudyInstanceUID,
                                                            AuditMessages.ParticipantObjectTypeCode.SystemObject,
                                                            AuditMessages.ParticipantObjectTypeCodeRole.Report)
                                                            .build();
        emitAuditMessage(auditLogger, ei, activeParticipantBuilder, studyPOI);
    }

    void spoolConnectionFailure(ConnectionEvent event) {
        try {
            writeSpoolFile(
                    AuditServiceUtils.EventType.CONN_FAILR,
                    ConnectionEventsAuditService.connFailureAuditInfo(event, device.getDeviceName()));
        } catch (Exception e) {
            LOG.warn("Failed to spool Connection Rejected : " + e);
        }
    }

    private void auditConnectionFailure(AuditLogger auditLogger, Path path, AuditServiceUtils.EventType eventType) {
        SpoolFileReader reader = new SpoolFileReader(path);
        AuditInfo auditInfo = new AuditInfo(reader.getMainInfo());

        emitAuditMessage(
                ConnectionEventsAuditService.auditMsg(auditInfo, eventType, getEventTime(path, auditLogger)),
                auditLogger);
    }

    void spoolQuery(QueryContext ctx) {
        try {
            boolean auditAggregate = getArchiveDevice().isAuditAggregate();
            AuditLoggerDeviceExtension ext = device.getDeviceExtension(AuditLoggerDeviceExtension.class);
            AuditServiceUtils.EventType eventType = AuditServiceUtils.EventType.QUERY__EVT;
            AuditInfo auditInfo = ctx.getHttpRequest() != null ? createAuditInfoForQIDO(ctx) : createAuditInfoForFIND(ctx);
            for (AuditLogger auditLogger : ext.getAuditLoggers()) {
                if (!isSpoolingSuppressed(eventType, ctx.getCallingAET(), auditLogger)) {
                    Path directory = toDirPath(auditLogger);
                    try {
                        Files.createDirectories(directory);
                        Path file = Files.createTempFile(directory, String.valueOf(eventType), null);
                        try (BufferedOutputStream out = new BufferedOutputStream(
                                Files.newOutputStream(file, StandardOpenOption.APPEND))) {
                            new DataOutputStream(out).writeUTF(auditInfo.toString());
                            if (ctx.getAssociation() != null) {
                                try (DicomOutputStream dos = new DicomOutputStream(out, UID.ImplicitVRLittleEndian)) {
                                    dos.writeDataset(null, ctx.getQueryKeys());
                                } catch (Exception e) {
                                    LOG.warn("Failed to create DicomOutputStream : ", e);
                                }
                            }
                        }
                        if (!auditAggregate)
                            auditAndProcessFile(auditLogger, file);
                    } catch (Exception e) {
                        LOG.warn("Failed to write to Audit Spool File - {}", auditLogger.getCommonName(), e);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to spool Query : " + e);
        }
    }

    private AuditInfo createAuditInfoForFIND(QueryContext ctx) {
        return new AuditInfo(
                new AuditInfoBuilder.Builder()
                        .callingHost(ctx.getRemoteHostName())
                        .callingUserID(ctx.getCallingAET())
                        .calledUserID(ctx.getCalledAET())
                        .queryPOID(ctx.getSOPClassUID())
                        .build());
    }

    private AuditInfo createAuditInfoForQIDO(QueryContext ctx) {
        HttpServletRequest httpRequest = ctx.getHttpRequest();
        return new AuditInfo(
                new AuditInfoBuilder.Builder()
                        .callingHost(ctx.getRemoteHostName())
                        .callingUserID(KeycloakContext.valueOf(ctx.getHttpRequest()).getUserName())
                        .calledUserID(httpRequest.getRequestURI())
                        .queryPOID(ctx.getSearchMethod())
                        .queryString(httpRequest.getRequestURI() + httpRequest.getQueryString())
                        .build());
    }

    private boolean isSpoolingSuppressed(AuditServiceUtils.EventType eventType, String userID, AuditLogger auditLogger) {
        return !auditLogger.isInstalled()
                || (!auditLogger.getAuditSuppressCriteriaList().isEmpty()
                    && auditLogger.isAuditMessageSuppressed(createMinimalAuditMsg(eventType, userID)));
    }

    private AuditMessage createMinimalAuditMsg(AuditServiceUtils.EventType eventType, String userID) {
        AuditMessage msg = new AuditMessage();
        msg.setEventIdentification(
                AuditMessages.toEventIdentification(toBuildEventIdentification(eventType, null, null)));
        ActiveParticipant ap = new ActiveParticipant();
        ap.setUserID(userID);
        ap.setUserIsRequestor(true);
        msg.getActiveParticipant().add(ap);
        return msg;
    }

    void auditAndProcessFile(AuditLogger auditLogger, Path file) {
        try {
            aggregateAuditMessage(auditLogger, file);
            Files.delete(file);
        } catch (Exception e) {
            LOG.warn("Failed to process Audit Spool File - {}", auditLogger.getCommonName(), file, e);
            try {
                Files.move(file, file.resolveSibling(file.getFileName().toString() + ".failed"));
            } catch (IOException e1) {
                LOG.warn("Failed to mark Audit Spool File - {} as failed", auditLogger.getCommonName(), file, e);
            }
        }
    }

    private void auditQuery(
            AuditLogger auditLogger, Path file, AuditServiceUtils.EventType eventType) throws IOException {
        AuditInfo qrI;
        ActiveParticipantBuilder[] activeParticipantBuilder = new ActiveParticipantBuilder[2];
        EventIdentificationBuilder ei = toBuildEventIdentification(eventType, null, getEventTime(file, auditLogger));
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            qrI = new AuditInfo(new DataInputStream(in).readUTF());
            String archiveUserID = qrI.getField(AuditInfo.CALLED_USERID);
            String callingUserID = qrI.getField(AuditInfo.CALLING_USERID);
            AuditMessages.UserIDTypeCode archiveUserIDTypeCode = archiveUserIDTypeCode(archiveUserID);
            activeParticipantBuilder[0] = new ActiveParticipantBuilder.Builder(
                                    callingUserID,
                                    qrI.getField(AuditInfo.CALLING_HOST))
                                    .userIDTypeCode(remoteUserIDTypeCode(archiveUserIDTypeCode, callingUserID))
                                    .isRequester()
                                    .roleIDCode(eventType.source)
                                    .build();
            activeParticipantBuilder[1] = new ActiveParticipantBuilder.Builder(
                                    archiveUserID,
                                    getLocalHostName(auditLogger))
                                    .userIDTypeCode(archiveUserIDTypeCode)
                                    .altUserID(AuditLogger.processID())
                                    .roleIDCode(eventType.destination)
                                    .build();
            ParticipantObjectIdentificationBuilder poi;
            if (archiveUserIDTypeCode == AuditMessages.UserIDTypeCode.URI) {
                poi = new ParticipantObjectIdentificationBuilder.Builder(
                        qrI.getField(AuditInfo.Q_POID),
                        AuditMessages.ParticipantObjectIDTypeCode.QIDO_QUERY,
                        AuditMessages.ParticipantObjectTypeCode.SystemObject,
                        AuditMessages.ParticipantObjectTypeCodeRole.Query)
                        .query(qrI.getField(AuditInfo.Q_STRING).getBytes())
                        .detail(getPod("QueryEncoding", String.valueOf(StandardCharsets.UTF_8)))
                        .build();
            }
            else {
                byte[] buffer = new byte[(int) Files.size(file)];
                int len = in.read(buffer);
                byte[] data;
                if (len != -1) {
                    data = new byte[len];
                    System.arraycopy(buffer, 0, data, 0, len);
                }
                else {
                    data = new byte[0];
                }
                poi = new ParticipantObjectIdentificationBuilder.Builder(
                        qrI.getField(AuditInfo.Q_POID),
                        AuditMessages.ParticipantObjectIDTypeCode.SOPClassUID,
                        AuditMessages.ParticipantObjectTypeCode.SystemObject,
                        AuditMessages.ParticipantObjectTypeCodeRole.Report)
                        .query(data)
                        .detail(getPod("TransferSyntax", UID.ImplicitVRLittleEndian))
                        .build();
            }
            emitAuditMessage(auditLogger, ei, activeParticipantBuilder, poi);
        }
    }

    void spoolStoreEvent(StoreContext ctx) {
        try {
            RejectionNote rejectionNote = ctx.getRejectionNote();
            if (rejectionNote != null && !rejectionNote.isRevokeRejection()) {
                spoolInstancesDeleted(ctx);
                return;
            }

            if (isDuplicateReceivedInstance(ctx)) {
                if (rejectionNote != null && rejectionNote.isRevokeRejection())
                    spoolInstancesStored(ctx);
                return;
            }

            if (ctx.getAttributes() == null) {
                LOG.warn("Instances stored is not audited as store context attributes are not set. "
                        + (ctx.getException() != null ? ctx.getException().getMessage() : null));
                return;
            }

            spoolInstancesStored(ctx);
        } catch (Exception e) {
            LOG.warn("Failed to spool Store Event : " + e);
        }
    }

    private void spoolInstancesStored(StoreContext ctx) {
        try {
            AuditServiceUtils.EventType eventType = AuditServiceUtils.EventType.forInstanceStored(ctx);

            StoreSession ss = ctx.getStoreSession();
            HttpServletRequest req = ss.getHttpRequest();
            String impaxReportEndpoint = ss.getImpaxReportEndpoint();
            String callingUserID = req != null
                    ? KeycloakContext.valueOf(req).getUserName()
                    : ss.getCallingAET();

            String outcome = ctx.getException() != null
                    ? ctx.getRejectionNote() != null
                    ? ctx.getRejectionNote().getRejectionNoteCode().getCodeMeaning() + '-' + ctx.getException().getMessage()
                    : ctx.getException().getMessage()
                    : null;

            AuditInfoBuilder instanceInfo = new AuditInfoBuilder.Builder()
                    .sopCUID(ctx.getSopClassUID()).sopIUID(ctx.getSopInstanceUID())
                    .mppsUID(ctx.getMppsInstanceUID())
                    .outcome(outcome)
                    .errorCode(ctx.getException() instanceof DicomServiceException ? ((DicomServiceException) ctx.getException()).getStatus() : 0)
                    .build();

            ArchiveDeviceExtension arcDev = getArchiveDevice();
            Attributes attr = ctx.getAttributes();
            AuditInfoBuilder info = new AuditInfoBuilder.Builder().callingHost(ss.getRemoteHostName())
                    .callingUserID(impaxReportEndpoint != null ? impaxReportEndpoint : callingUserID)
                    .calledUserID(req != null ? req.getRequestURI() : ss.getCalledAET())
                    .studyUIDAccNumDate(attr)
                    .pIDAndName(attr, arcDev)
                    .warning(ctx.getRejectionNote() != null
                            ? ctx.getRejectionNote().getRejectionNoteCode().getCodeMeaning() : null)
                    .build();

            String fileName = eventType.name()
                                + '-' + callingUserID.replace('|', '-')
                                + '-' + ctx.getStoreSession().getCalledAET()
                                + '-' + ctx.getStudyInstanceUID();
            fileName = outcome != null ? fileName.concat("_ERROR") : fileName;
            writeSpoolFileStoreOrWadoRetrieve(fileName, info, instanceInfo);
            if (ctx.getImpaxReportPatientMismatch() != null) {
                AuditInfoBuilder patMismatchInfo = new AuditInfoBuilder.Builder().callingHost(ss.getRemoteHostName())
                        .callingUserID(impaxReportEndpoint != null ? impaxReportEndpoint : callingUserID)
                        .calledUserID(req != null ? req.getRequestURI() : ss.getCalledAET())
                        .studyUIDAccNumDate(attr)
                        .pIDAndName(attr, arcDev)
                        .patMismatchCode(ctx.getImpaxReportPatientMismatch().toString())
                        .build();
                writeSpoolFile(AuditServiceUtils.EventType.IMPAX_MISM, patMismatchInfo, instanceInfo);
            }
        } catch (Exception e) {
            LOG.warn("Failed to spool Instances Stored : " + e);
        }
    }

    private void auditPatientMismatch(AuditLogger logger, Path path, AuditServiceUtils.EventType eventType) {
        SpoolFileReader reader = new SpoolFileReader(path);
        AuditInfo auditInfo = new AuditInfo(reader.getMainInfo());
        AuditMessages.EventTypeCode eventTypeCode = patMismatchEventTypeCode(auditInfo);
        EventIdentificationBuilder ei = new EventIdentificationBuilder.Builder(
                                            eventType.eventID,
                                            eventType.eventActionCode,
                                            getEventTime(path, logger),
                                            AuditMessages.EventOutcomeIndicator.MinorFailure)
                                            .outcomeDesc(eventTypeCode.getOriginalText())
                                            .eventTypeCode(eventTypeCode)
                                            .build();
        ActiveParticipantBuilder[] activeParticipantBuilder = new ActiveParticipantBuilder[2];
        String callingUserID = auditInfo.getField(AuditInfo.CALLING_USERID);
        String calledUserID = auditInfo.getField(AuditInfo.CALLED_USERID);
        activeParticipantBuilder[0] = new ActiveParticipantBuilder.Builder(calledUserID, getLocalHostName(logger))
                .userIDTypeCode(archiveUserIDTypeCode(calledUserID)).build();
        activeParticipantBuilder[1] = new ActiveParticipantBuilder.Builder(
                callingUserID, auditInfo.getField(AuditInfo.CALLING_HOST))
                .userIDTypeCode(AuditMessages.userIDTypeCode(callingUserID))
                .isRequester().build();
        emitAuditMessage(logger,
                ei,
                activeParticipantBuilder,
                storeStudyPOI(auditInfo, studyParticipantObjDesc(reader, auditInfo)),
                patientPOI(auditInfo));
    }

    private AuditMessages.EventTypeCode patMismatchEventTypeCode(AuditInfo auditInfo) {
        String patMismatchCode = auditInfo.getField(AuditInfo.PAT_MISMATCH_CODE);
        String[] code = patMismatchCode.substring(1, patMismatchCode.length() - 1).split(",");
        return new AuditMessages.EventTypeCode(code[0], code[1], code[2]);
    }

    private boolean isDuplicateReceivedInstance(StoreContext ctx) {
        return ctx.getLocations().isEmpty() && ctx.getStoredInstance() == null && ctx.getException() == null;
    }
    
    void spoolRetrieveWADO(RetrieveContext ctx) {
        try {
            HttpServletRequestInfo req = ctx.getHttpServletRequestInfo();
            Collection<InstanceLocations> il = ctx.getMatches();
            Attributes attrs = new Attributes();
            for (InstanceLocations i : il)
                attrs = i.getAttributes();
            String fileName = AuditServiceUtils.EventType.WADO___URI.name()
                                + '-' + req.requesterHost
                                + '-' + ctx.getLocalAETitle()
                                + '-' + ctx.getStudyInstanceUIDs()[0];
            AuditInfoBuilder info = new AuditInfoBuilder.Builder()
                    .callingHost(req.requesterHost)
                    .callingUserID(req.requesterUserID)
                    .calledUserID(req.requestURI)
                    .studyUIDAccNumDate(attrs)
                    .pIDAndName(attrs, getArchiveDevice())
                    .outcome(null != ctx.getException() ? ctx.getException().getMessage() : null)
                    .build();
            AuditInfoBuilder instanceInfo = new AuditInfoBuilder.Builder()
                    .sopCUID(attrs.getString(Tag.SOPClassUID))
                    .sopIUID(ctx.getSopInstanceUIDs()[0])
                    .build();
            writeSpoolFileStoreOrWadoRetrieve(fileName, info, instanceInfo);
        } catch (Exception e) {
            LOG.warn("Failed to spool Wado Retrieve : " + e);
        }
    }

    private HashMap<String, HashSet<String>> buildSOPClassMap(SpoolFileReader reader) {
        HashMap<String, HashSet<String>> sopClassMap = new HashMap<>();
        for (String line : reader.getInstanceLines()) {
            AuditInfo ii = new AuditInfo(line);
            sopClassMap.computeIfAbsent(ii.getField(AuditInfo.SOP_CUID), k -> new HashSet<>()).add(ii.getField(AuditInfo.SOP_IUID));
        }
        return sopClassMap;
    }

    private void auditStoreError(AuditLogger auditLogger, Path path, AuditServiceUtils.EventType eventType) {
        SpoolFileReader reader = new SpoolFileReader(path);
        AuditInfo auditInfo = new AuditInfo(reader.getMainInfo());

        HashSet<String> mpps = new HashSet<>();
        HashSet<String> outcome = new HashSet<>();
        HashSet<AuditMessages.EventTypeCode> errorCode = new HashSet<>();
        HashMap<String, HashSet<String>> sopClassMap = new HashMap<>();

        for (String line : reader.getInstanceLines()) {
            AuditInfo instanceInfo = new AuditInfo(line);
            outcome.add(instanceInfo.getField(AuditInfo.OUTCOME));
            AuditMessages.EventTypeCode errorEventTypeCode = AuditServiceUtils.errorEventTypeCode(instanceInfo.getField(AuditInfo.ERROR_CODE));
            if (errorEventTypeCode != null)
                errorCode.add(errorEventTypeCode);
            String mppsUID = instanceInfo.getField(AuditInfo.MPPS_UID);
            if (mppsUID != null)
                mpps.add(mppsUID);
            sopClassMap.computeIfAbsent(
                    instanceInfo.getField(AuditInfo.SOP_CUID),
                    k -> new HashSet<>()).add(instanceInfo.getField(AuditInfo.SOP_IUID));
        }

        EventIdentificationBuilder ei = new EventIdentificationBuilder.Builder(
                eventType.eventID,
                eventType.eventActionCode,
                getEventTime(path, auditLogger),
                AuditMessages.EventOutcomeIndicator.MinorFailure)
                .outcomeDesc(outcome.stream().collect(Collectors.joining("\n")))
                .eventTypeCode(errorCode.toArray(new AuditMessages.EventTypeCode[errorCode.size()]))
                .build();

        ParticipantObjectDescriptionBuilder desc = new ParticipantObjectDescriptionBuilder.Builder()
                .sopC(toSOPClasses(sopClassMap, true))
                .acc(auditInfo.getField(AuditInfo.ACC_NUM))
                .mpps(mpps.toArray(new String[mpps.size()]))
                .build();

        emitAuditMessage(auditLogger, ei,
                storeWadoURIActiveParticipants(auditLogger, auditInfo, eventType),
                storeStudyPOI(auditInfo, desc),
                patientPOI(auditInfo));
    }

    private void auditStoreOrWADORetrieve(AuditLogger auditLogger, Path path, AuditServiceUtils.EventType eventType) {
        if (path.toFile().getName().endsWith("_ERROR")) {
            auditStoreError(auditLogger, path, eventType);
            return;
        }

        if (eventType.name().startsWith("WADO")) {
            auditWADORetrieve(auditLogger, path, eventType);
            return;
        }

        SpoolFileReader reader = new SpoolFileReader(path);
        AuditInfo auditInfo = new AuditInfo(reader.getMainInfo());
        EventIdentificationBuilder ei = toCustomBuildEventIdentification(
                                            eventType,
                                            auditInfo.getField(AuditInfo.OUTCOME),
                                            auditInfo.getField(AuditInfo.WARNING),
                                            getEventTime(path, auditLogger));

        emitAuditMessage(auditLogger, ei,
                storeWadoURIActiveParticipants(auditLogger, auditInfo, eventType),
                storeStudyPOI(auditInfo, studyParticipantObjDesc(reader, auditInfo)),
                patientPOI(auditInfo));
    }

    private ParticipantObjectDescriptionBuilder studyParticipantObjDesc(SpoolFileReader reader, AuditInfo auditInfo) {
        HashSet<String> mpps = new HashSet<>();
        HashMap<String, HashSet<String>> sopClassMap = new HashMap<>();

        for (String line : reader.getInstanceLines()) {
            AuditInfo instanceInfo = new AuditInfo(line);
            sopClassMap.computeIfAbsent(
                    instanceInfo.getField(AuditInfo.SOP_CUID),
                    k -> new HashSet<>()).add(instanceInfo.getField(AuditInfo.SOP_IUID));
            String mppsUID = instanceInfo.getField(AuditInfo.MPPS_UID);
            if (mppsUID != null)
                mpps.add(mppsUID);
        }

        return new ParticipantObjectDescriptionBuilder.Builder()
                .sopC(toSOPClasses(sopClassMap, false))
                .acc(auditInfo.getField(AuditInfo.ACC_NUM))
                .mpps(mpps.toArray(new String[mpps.size()]))
                .build();
    }

    private ParticipantObjectIdentificationBuilder storeStudyPOI(AuditInfo auditInfo, ParticipantObjectDescriptionBuilder desc) {
        return new ParticipantObjectIdentificationBuilder.Builder(
                auditInfo.getField(AuditInfo.STUDY_UID),
                AuditMessages.ParticipantObjectIDTypeCode.StudyInstanceUID,
                AuditMessages.ParticipantObjectTypeCode.SystemObject,
                AuditMessages.ParticipantObjectTypeCodeRole.Report)
                .desc(desc)
                .detail(getPod(studyDate, auditInfo.getField(AuditInfo.STUDY_DATE)))
                .lifeCycle(AuditMessages.ParticipantObjectDataLifeCycle.OriginationCreation)
                .build();
    }

    private void auditWADORetrieve(AuditLogger auditLogger, Path path, AuditServiceUtils.EventType eventType) {
        SpoolFileReader reader = new SpoolFileReader(path);
        AuditInfo auditInfo = new AuditInfo(reader.getMainInfo());
        EventIdentificationBuilder ei = toBuildEventIdentification(
                eventType,
                auditInfo.getField(AuditInfo.OUTCOME),
                getEventTime(path, auditLogger));

        ParticipantObjectDescriptionBuilder desc = new ParticipantObjectDescriptionBuilder.Builder()
                .sopC(toSOPClasses(buildSOPClassMap(reader), auditInfo.getField(AuditInfo.OUTCOME) != null))
                .acc(auditInfo.getField(AuditInfo.ACC_NUM))
                .build();

        ParticipantObjectIdentificationBuilder studyPOI =  new ParticipantObjectIdentificationBuilder.Builder(
                auditInfo.getField(AuditInfo.STUDY_UID),
                AuditMessages.ParticipantObjectIDTypeCode.StudyInstanceUID,
                AuditMessages.ParticipantObjectTypeCode.SystemObject,
                AuditMessages.ParticipantObjectTypeCodeRole.Report)
                .desc(desc)
                .detail(getPod(studyDate, auditInfo.getField(AuditInfo.STUDY_DATE)))
                .build();
        emitAuditMessage(auditLogger, ei,
                storeWadoURIActiveParticipants(auditLogger, auditInfo, eventType),
                studyPOI,
                patientPOI(auditInfo));
    }

    private ActiveParticipantBuilder[] storeWadoURIActiveParticipants(
            AuditLogger auditLogger, AuditInfo auditInfo, AuditServiceUtils.EventType eventType) {
        ActiveParticipantBuilder[] activeParticipantBuilder = new ActiveParticipantBuilder[2];
        String callingUserID = auditInfo.getField(AuditInfo.CALLING_USERID);
        String archiveUserID = auditInfo.getField(AuditInfo.CALLED_USERID);
        AuditMessages.UserIDTypeCode archiveUserIDTypeCode = archiveUserIDTypeCode(archiveUserID);
        activeParticipantBuilder[0] = new ActiveParticipantBuilder.Builder(
                callingUserID,
                auditInfo.getField(AuditInfo.CALLING_HOST))
                .userIDTypeCode(remoteUserIDTypeCode(archiveUserIDTypeCode, callingUserID))
                .isRequester()
                .roleIDCode(eventType.source)
                .build();
        activeParticipantBuilder[1] = new ActiveParticipantBuilder.Builder(
                archiveUserID,
                getLocalHostName(auditLogger))
                .userIDTypeCode(archiveUserIDTypeCode)
                .altUserID(AuditLogger.processID())
                .roleIDCode(eventType.destination)
                .build();
        return activeParticipantBuilder;
    }

    void spoolRetrieve(AuditServiceUtils.EventType eventType, RetrieveContext ctx) {
        try {
            RetrieveAuditService retrieveAuditService = new RetrieveAuditService(ctx, getArchiveDevice());
            for (AuditInfoBuilder[] auditInfoBuilder : retrieveAuditService.getAuditInfoBuilder())
                writeSpoolFile(eventType, auditInfoBuilder);
        } catch (Exception e) {
            LOG.warn("Failed to spool Retrieve : " + e);
        }
    }

    private ArchiveDeviceExtension getArchiveDevice() {
        return device.getDeviceExtension(ArchiveDeviceExtension.class);
    }

    private void auditRetrieve(AuditLogger auditLogger, Path path, AuditServiceUtils.EventType eventType) {
        SpoolFileReader reader = new SpoolFileReader(path);
        AuditInfo auditInfo = new AuditInfo(reader.getMainInfo());
        EventIdentificationBuilder ei = toCustomBuildEventIdentification(eventType, auditInfo.getField(AuditInfo.OUTCOME),
                auditInfo.getField(AuditInfo.WARNING), getEventTime(path, auditLogger));

        emitAuditMessage(
                RetrieveAuditService.auditMsg(eventType, auditInfo, auditLogger, ei, reader),
                auditLogger);
    }

    void spoolHL7Message(HL7ConnectionEvent hl7ConnEvent) {
        if (hl7ConnEvent.getHL7ResponseMessage() == null)
            return;

        HL7ConnectionEvent.Type type = hl7ConnEvent.getType();
        if (type == HL7ConnectionEvent.Type.MESSAGE_PROCESSED)
            spoolIncomingHL7PatRecMsg(hl7ConnEvent);
        if (type == HL7ConnectionEvent.Type.MESSAGE_RESPONSE)
            spoolOutgoingHL7PatRecMsg(hl7ConnEvent);
    }

    private void spoolIncomingHL7PatRecMsg(HL7ConnectionEvent hl7ConnEvent) {
        UnparsedHL7Message hl7ResponseMessage = hl7ConnEvent.getHL7ResponseMessage();
        UnparsedHL7Message hl7Message = hl7ConnEvent.getHL7Message();
        HL7Segment pid = getHL7Segment(hl7Message, "PID");
        HL7Segment msh = hl7Message.msh();
        String sendingApplicationWithFacility = msh.getSendingApplicationWithFacility();
        String receivingApplicationWithFacility = msh.getReceivingApplicationWithFacility();
        String outcome = outcome(hl7ConnEvent.getException());
        String hostname = hl7ConnEvent.getConnection().getHostname();
        HL7Segment mrg = getHL7Segment(hl7Message, "MRG");
        HL7Segment orc = getHL7Segment(hl7Message, "ORC");
        ArchiveDeviceExtension arcDev = getArchiveDevice();
        AuditServiceUtils.EventType eventType = AuditServiceUtils.EventType.forHL7PatRec(hl7ResponseMessage);

        if (orc != null)
            spoolIncomingHL7OrderMsg(hl7ConnEvent);

        AuditInfoBuilder info = new AuditInfoBuilder.Builder()
                .callingHost(hostname)
                .callingUserID(sendingApplicationWithFacility)
                .calledUserID(receivingApplicationWithFacility)
                .patID(pid.getField(3, null), arcDev)
                .patName(pid.getField(5, null), arcDev)
                .outcome(outcome)
                .build();
        writeSpoolFile(eventType, info, hl7Message.data(), hl7ResponseMessage.data());

        if (mrg != null && eventType != AuditServiceUtils.EventType.PAT___READ) {//spool below only for successful changePID or merge
            AuditInfoBuilder prev = new AuditInfoBuilder.Builder()
                    .callingHost(hostname)
                    .callingUserID(sendingApplicationWithFacility)
                    .calledUserID(receivingApplicationWithFacility)
                    .patID(mrg.getField(1, null), arcDev)
                    .patName(mrg.getField(7, null), arcDev)
                    .outcome(outcome)
                    .build();
            writeSpoolFile(AuditServiceUtils.EventType.PAT_DELETE, prev, hl7Message.data(), hl7ResponseMessage.data());
        }
    }

    private void spoolIncomingHL7OrderMsg(HL7ConnectionEvent hl7ConnEvent) {
        UnparsedHL7Message hl7ResponseMessage = hl7ConnEvent.getHL7ResponseMessage();
        UnparsedHL7Message hl7Message = hl7ConnEvent.getHL7Message();
        String messageType = hl7Message.msh().getMessageType();
        HL7Segment pid = getHL7Segment(hl7Message, "PID");

        AuditInfoBuilder infoProcedure = isOrderProcessed(messageType)
                ? incomingProcProcessed(hl7ConnEvent, (ArchiveHL7Message) hl7ResponseMessage, pid)
                : incomingProcAcknowledged(hl7ConnEvent, pid);

        writeSpoolFile(
                AuditServiceUtils.EventType.forHL7OrderMsg(hl7ResponseMessage),
                infoProcedure,
                hl7Message.data(),
                hl7ConnEvent.getHL7ResponseMessage().data());
    }

    private boolean isOrderProcessed(String messageType) {
        return messageType.equals("ORM^O01") || messageType.equals("OMG^O19") || messageType.equals("OMI^O23");
    }

    private AuditInfoBuilder incomingProcProcessed(HL7ConnectionEvent hl7ConnEvent, ArchiveHL7Message archiveHL7Message,
                                                   HL7Segment pid) {
        UnparsedHL7Message hl7Message = hl7ConnEvent.getHL7Message();
        HL7Segment msh = hl7Message.msh();
        return new AuditInfoBuilder.Builder()
                .callingHost(hl7ConnEvent.getConnection().getHostname())
                .callingUserID(msh.getSendingApplicationWithFacility())
                .calledUserID(msh.getReceivingApplicationWithFacility())
                .studyUIDAccNumDate(archiveHL7Message.getStudyAttrs())
                .patID(pid.getField(3, null), getArchiveDevice())
                .patName(pid.getField(5, null), getArchiveDevice())
                .outcome(outcome(hl7ConnEvent.getException()))
                .build();
    }

    private AuditInfoBuilder incomingProcAcknowledged(HL7ConnectionEvent hl7ConnEvent, HL7Segment pid) {
        UnparsedHL7Message hl7Message = hl7ConnEvent.getHL7Message();
        HL7Segment msh = hl7Message.msh();
        return new AuditInfoBuilder.Builder()
                .callingHost(hl7ConnEvent.getConnection().getHostname())
                .callingUserID(msh.getSendingApplicationWithFacility())
                .calledUserID(msh.getReceivingApplicationWithFacility())
                .studyIUID(getArchiveDevice().auditUnknownStudyInstanceUID())
                .patID(pid.getField(3, null), getArchiveDevice())
                .patName(pid.getField(5, null), getArchiveDevice())
                .outcome(outcome(hl7ConnEvent.getException()))
                .build();
    }

    private HL7Segment getHL7Segment(UnparsedHL7Message hl7Message, String segName) {
        HL7Segment msh = hl7Message.msh();
        String charset = msh.getField(17, "ASCII");
        HL7Message msg = HL7Message.parse(hl7Message.data(), charset);
        return msg.getSegment(segName);
    }

    private void spoolOutgoingHL7PatRecMsg(HL7ConnectionEvent hl7ConnEvent) {
        UnparsedHL7Message hl7ResponseMessage = hl7ConnEvent.getHL7ResponseMessage();
        Socket socket = hl7ConnEvent.getSocket();
        String outcome = outcome(hl7ConnEvent.getException());
        UnparsedHL7Message hl7Message = hl7ConnEvent.getHL7Message();
        HL7Segment msh = hl7Message.msh();
        String sendingApplicationWithFacility = msh.getSendingApplicationWithFacility();
        String receivingApplicationWithFacility = msh.getReceivingApplicationWithFacility();
        String messageType = msh.getMessageType();

        HL7Segment pid = getHL7Segment(hl7Message, "PID");
        HL7Segment mrg = getHL7Segment(hl7Message, "MRG");

        ArchiveDeviceExtension arcDev = getArchiveDevice();
        boolean isOrderMsg = isOrderProcessed(messageType);

        AuditServiceUtils.EventType eventType = messageType.equals("ADT^A28") || messageType.equals("ORU^R01")
                ? AuditServiceUtils.EventType.PAT_CREATE
                : AuditServiceUtils.EventType.PAT_UPDATE;

        if (hl7Message instanceof ArchiveHL7Message && !isOrderMsg) {
            ArchiveHL7Message archiveHL7Message = (ArchiveHL7Message) hl7Message;
            HttpServletRequestInfo httpServletRequestInfo = archiveHL7Message.getHttpServletRequestInfo();
            String callingHost = httpServletRequestInfo != null
                    ? httpServletRequestInfo.requesterHost
                    : socket != null
                    ? ReverseDNS.hostNameOf(socket.getInetAddress()) : null;
            String callingUserID = httpServletRequestInfo != null
                    ? httpServletRequestInfo.requesterUserID
                    : sendingApplicationWithFacility;
            String calledUserID = httpServletRequestInfo != null
                    ? httpServletRequestInfo.requestURI
                    : receivingApplicationWithFacility;
            AuditInfoBuilder info = new AuditInfoBuilder.Builder()
                    .callingHost(callingHost)
                    .callingUserID(callingUserID)
                    .calledUserID(calledUserID)
                    .patID(pid.getField(3, null), arcDev)
                    .patName(pid.getField(5, null), arcDev)
                    .outcome(outcome)
                    .isOutgoingHL7()
                    .outgoingHL7Sender(sendingApplicationWithFacility)
                    .outgoingHL7Receiver(receivingApplicationWithFacility)
                    .build();
            writeSpoolFile(eventType, info, hl7Message.data(), hl7ResponseMessage.data());
            if (mrg != null) {//to be kept consistent
                AuditInfoBuilder prev = new AuditInfoBuilder.Builder()
                        .callingHost(callingHost)
                        .callingUserID(callingUserID)
                        .calledUserID(calledUserID)
                        .patID(pid.getField(3, null), arcDev)
                        .patName(pid.getField(5, null), arcDev)
                        .outcome(outcome)
                        .isOutgoingHL7()
                        .outgoingHL7Sender(sendingApplicationWithFacility)
                        .outgoingHL7Receiver(receivingApplicationWithFacility)
                        .build();
                writeSpoolFile(AuditServiceUtils.EventType.PAT_DELETE, prev, hl7Message.data(), hl7ResponseMessage.data());
            }
        }

        if (isOrderMsg)
            spoolOutgoingHL7OrderMsg(hl7ConnEvent);
    }

    private void spoolOutgoingHL7OrderMsg(HL7ConnectionEvent hl7ConnEvent) {
        UnparsedHL7Message hl7Message = hl7ConnEvent.getHL7Message();
        HL7Segment msh = hl7Message.msh();
        HL7Segment pid = getHL7Segment(hl7Message, "PID");

        AuditServiceUtils.EventType eventType = AuditServiceUtils.EventType.PROC_STD_R;
        HL7DeviceExtension hl7Dev = device.getDeviceExtension(HL7DeviceExtension.class);
        ArchiveHL7ApplicationExtension hl7AppExt = hl7Dev.getHL7Application(msh.getSendingApplicationWithFacility(), true)
                .getHL7ApplicationExtension(ArchiveHL7ApplicationExtension.class);
        HL7Segment orc = getHL7Segment(hl7Message, "ORC");

        String orderControlOrderStatus = orc.getField(1, null) + "_" + orc.getField(5, null);
        Collection<HL7OrderSPSStatus> hl7OrderSPSStatuses = hl7AppExt.hl7OrderSPSStatuses();
        for (HL7OrderSPSStatus hl7OrderSPSStatus : hl7OrderSPSStatuses) {
            String[] orderControlStatusCodes = hl7OrderSPSStatus.getOrderControlStatusCodes();
            if (hl7OrderSPSStatus.getSPSStatus() == SPSStatus.SCHEDULED) {
                if (Arrays.asList(orderControlStatusCodes).contains(orderControlOrderStatus)) {
                    eventType = AuditServiceUtils.EventType.PROC_STD_C;
                    break;
                }
            } else {
                if (Arrays.asList(orderControlStatusCodes).contains(orderControlOrderStatus)
                        || orderControlOrderStatus.equals("SC_CM")) {   //SC_CM = archive sends proc update msg mpps or study receive trigger
                    eventType = AuditServiceUtils.EventType.PROC_STD_U;
                    break;
                }
            }
        }

        writeSpoolFile(eventType,
                pid != null ? outgoingProcRecForward(hl7ConnEvent, pid) : outgoingProcRecUpdate(hl7ConnEvent),
                hl7Message.data(),
                hl7ConnEvent.getHL7ResponseMessage().data());
    }

    private AuditInfoBuilder outgoingProcRecForward(HL7ConnectionEvent hl7ConnEvent, HL7Segment pid) {
        UnparsedHL7Message hl7Message = hl7ConnEvent.getHL7Message();
        HL7Segment msh = hl7Message.msh();
        ArchiveDeviceExtension arcDev = getArchiveDevice();
        return new AuditInfoBuilder.Builder()
                .callingHost(ReverseDNS.hostNameOf(hl7ConnEvent.getSocket().getInetAddress()))
                .callingUserID(msh.getSendingApplicationWithFacility())
                .calledUserID(msh.getReceivingApplicationWithFacility())
                .studyIUID(procRecHL7StudyIUID(hl7Message))
                .accNum(procRecHL7Acc(hl7Message))
                .patID(pid.getField(3, null), arcDev)
                .patName(pid.getField(5, null), arcDev)
                .outcome(outcome(hl7ConnEvent.getException()))
                .isOutgoingHL7()
                .outgoingHL7Sender(msh.getSendingApplicationWithFacility())
                .outgoingHL7Receiver(msh.getReceivingApplicationWithFacility())
                .build();
    }

    private AuditInfoBuilder outgoingProcRecUpdate(HL7ConnectionEvent hl7ConnEvent) {
        UnparsedHL7Message hl7Message = hl7ConnEvent.getHL7Message();
        HL7Segment msh = hl7Message.msh();
        return new AuditInfoBuilder.Builder()
                .callingHost(ReverseDNS.hostNameOf(hl7ConnEvent.getSocket().getInetAddress()))
                .callingUserID(msh.getSendingApplicationWithFacility())
                .calledUserID(msh.getReceivingApplicationWithFacility())
                .studyIUID(procRecHL7StudyIUID(hl7Message))
                .accNum(procRecHL7Acc(hl7Message))
                .outcome(outcome(hl7ConnEvent.getException()))
                .isOutgoingHL7()
                .outgoingHL7Sender(msh.getSendingApplicationWithFacility())
                .outgoingHL7Receiver(msh.getReceivingApplicationWithFacility())
                .build();
    }

    private String procRecHL7StudyIUID(UnparsedHL7Message hl7Message) {
        HL7Segment zds = getHL7Segment(hl7Message, "ZDS");
        HL7Segment ipc = getHL7Segment(hl7Message, "IPC");
        return zds != null
                ? zds.getField(1, null)
                : ipc != null
                ? ipc.getField(3, null) : getArchiveDevice().auditUnknownStudyInstanceUID();
    }

    private String procRecHL7Acc(UnparsedHL7Message hl7Message) {
        HL7Segment obr = getHL7Segment(hl7Message, "OBR");
        HL7Segment ipc = getHL7Segment(hl7Message, "IPC");
        return ipc != null
                ? ipc.getField(1, null)
                : obr != null
                ? obr.getField(18, null) : null;
    }

    void spoolPatientRecord(PatientMgtContext ctx) {
        if (ctx.getUnparsedHL7Message() != null)
            return;

        try {
            HttpServletRequestInfo httpRequest = ctx.getHttpServletRequestInfo();
            Association association = ctx.getAssociation();
            String callingUserID = httpRequest != null
                    ? httpRequest.requesterUserID
                    : association != null
                        ? association.getCallingAET() : null;
            String calledUserID = httpRequest != null
                    ? httpRequest.requestURI
                    : association != null
                        ? association.getCalledAET() : null;
            AuditInfoBuilder patInfo = new AuditInfoBuilder.Builder()
                    .callingHost(ctx.getRemoteHostName())
                    .callingUserID(callingUserID)
                    .calledUserID(calledUserID)
                    .pIDAndName(ctx.getAttributes(), getArchiveDevice())
                    .outcome(outcome(ctx.getException()))
                    .build();
            writeSpoolFile(AuditServiceUtils.EventType.forPatRec(ctx), patInfo);
            if (ctx.getPreviousAttributes() != null) {
                AuditInfoBuilder prevPatInfo = new AuditInfoBuilder.Builder()
                        .callingHost(ctx.getRemoteHostName())
                        .callingUserID(callingUserID)
                        .calledUserID(calledUserID)
                        .pIDAndName(ctx.getPreviousAttributes(), getArchiveDevice())
                        .outcome(outcome(ctx.getException()))
                        .build();
                writeSpoolFile(AuditServiceUtils.EventType.PAT_DELETE, prevPatInfo);
            }
        } catch (Exception e) {
            LOG.warn("Failed to spool Patient Record : " + e);
        }
    }

    private void auditPatientRecord(AuditLogger auditLogger, Path path, AuditServiceUtils.EventType et) throws Exception {
        SpoolFileReader reader = new SpoolFileReader(path.toFile());
        AuditInfo auditInfo = new AuditInfo(reader.getMainInfo());
        EventIdentificationBuilder ei = toBuildEventIdentification(et, auditInfo.getField(AuditInfo.OUTCOME), getEventTime(path, auditLogger));
        ActiveParticipantBuilder[] activeParticipantBuilder = isServiceUserTriggered(et.source)
                ? auditInfo.getField(AuditInfo.IS_OUTGOING_HL7) != null
                    ? getOutgoingPatientRecordActiveParticipants(auditLogger, et, auditInfo)
                    : getInternalPatientRecordActiveParticipants(auditLogger, et, auditInfo)
                : getSchedulerTriggeredActiveParticipant(auditLogger, et);

        ParticipantObjectIdentificationBuilder patientPOI = new ParticipantObjectIdentificationBuilder.Builder(
                                                                auditInfo.getField(AuditInfo.P_ID),
                                                                AuditMessages.ParticipantObjectIDTypeCode.PatientNumber,
                                                                AuditMessages.ParticipantObjectTypeCode.Person,
                                                                AuditMessages.ParticipantObjectTypeCodeRole.Patient)
                                                                .name(auditInfo.getField(AuditInfo.P_NAME))
                                                                .detail(getHL7ParticipantObjectDetail(reader))
                                                                .build();

        emitAuditMessage(auditLogger, ei, activeParticipantBuilder, patientPOI);
    }

    private ParticipantObjectDetail[] getHL7ParticipantObjectDetail(SpoolFileReader reader) {
        ParticipantObjectDetail[] detail = new ParticipantObjectDetail[2];
        setParticipantObjectDetail(reader.getData(), 0, detail);
        setParticipantObjectDetail(reader.getAck(), 1, detail);
        return detail;
    }

    private void setParticipantObjectDetail(byte[] val, int index, ParticipantObjectDetail[] detail) {
        if (val.length > 0) {
            detail[index] = new ParticipantObjectDetail();
            detail[index].setType("HL7v2 Message");
            detail[index].setValue(val);
        }
    }

    private ActiveParticipantBuilder[] getSchedulerTriggeredActiveParticipant(AuditLogger auditLogger, AuditServiceUtils.EventType et) {
        ActiveParticipantBuilder[] activeParticipantBuilder = new ActiveParticipantBuilder[1];
        activeParticipantBuilder[0] = new ActiveParticipantBuilder.Builder(
                device.getDeviceName(),
                getLocalHostName(auditLogger))
                .userIDTypeCode(AuditMessages.UserIDTypeCode.DeviceName)
                .altUserID(AuditLogger.processID())
                .isRequester()
                .roleIDCode(et.destination)
                .build();
        return activeParticipantBuilder;
    }

    private ActiveParticipantBuilder[] getInternalPatientRecordActiveParticipants(
            AuditLogger auditLogger, AuditServiceUtils.EventType et, AuditInfo auditInfo) {
        ActiveParticipantBuilder[] activeParticipantBuilder = new ActiveParticipantBuilder[2];
        String archiveUserID = auditInfo.getField(AuditInfo.CALLED_USERID);
        String callingUserID = auditInfo.getField(AuditInfo.CALLING_USERID);
        AuditMessages.UserIDTypeCode archiveUserIDTypeCode = archiveUserIDTypeCode(archiveUserID);

        activeParticipantBuilder[0] = new ActiveParticipantBuilder.Builder(
                                archiveUserID,
                                getLocalHostName(auditLogger))
                                .userIDTypeCode(archiveUserIDTypeCode)
                                .altUserID(AuditLogger.processID())
                                .roleIDCode(et.destination)
                                .build();
        activeParticipantBuilder[1] = new ActiveParticipantBuilder.Builder(
                                callingUserID,
                                auditInfo.getField(AuditInfo.CALLING_HOST))
                                .userIDTypeCode(remoteUserIDTypeCode(archiveUserIDTypeCode, callingUserID))
                                .isRequester()
                                .roleIDCode(et.source)
                                .build();
        return activeParticipantBuilder;
    }

    private ActiveParticipantBuilder[] getOutgoingPatientRecordActiveParticipants(
            AuditLogger auditLogger, AuditServiceUtils.EventType et, AuditInfo auditInfo) throws ConfigurationException {
        ActiveParticipantBuilder[] activeParticipantBuilder = new ActiveParticipantBuilder[4];
        HL7DeviceExtension hl7Dev = device.getDeviceExtension(HL7DeviceExtension.class);

        String calledUserID = auditInfo.getField(AuditInfo.CALLED_USERID);
        String callingUserID = auditInfo.getField(AuditInfo.CALLING_USERID);

        String hl7SendingAppWithFacility = auditInfo.getField(AuditInfo.OUTGOING_HL7_SENDER);
        String hl7ReceivingAppWithFacility = auditInfo.getField(AuditInfo.OUTGOING_HL7_RECEIVER);

        HL7Application hl7AppSender = hl7Dev.getHL7Application(hl7SendingAppWithFacility, true);
        HL7Application hl7AppReceiver = hl7AppCache.findHL7Application(hl7ReceivingAppWithFacility);

        boolean isHL7Forward = hl7SendingAppWithFacility.equals(callingUserID)
                                && hl7ReceivingAppWithFacility.equals(calledUserID);

        activeParticipantBuilder[0] = new ActiveParticipantBuilder.Builder(
                                        hl7SendingAppWithFacility,
                                        hl7AppSender.getConnections().get(0).getHostname())
                                        .userIDTypeCode(AuditMessages.UserIDTypeCode.ApplicationFacility)
                                        .roleIDCode(et.source)
                                        .build();
        activeParticipantBuilder[1] = new ActiveParticipantBuilder.Builder(
                                        hl7ReceivingAppWithFacility,
                                        hl7AppReceiver.getConnections().get(0).getHostname())
                                        .userIDTypeCode(AuditMessages.UserIDTypeCode.ApplicationFacility)
                                        .roleIDCode(et.destination)
                                        .build();
        if (isHL7Forward)
            activeParticipantBuilder[2] = new ActiveParticipantBuilder.Builder(
                    device.getDeviceName(),
                    getLocalHostName(auditLogger))
                    .userIDTypeCode(AuditMessages.UserIDTypeCode.DeviceName)
                    .altUserID(AuditLogger.processID())
                    .isRequester()
                    .build();
        else {
            activeParticipantBuilder[2] = new ActiveParticipantBuilder.Builder(
                    callingUserID,
                    auditInfo.getField(AuditInfo.CALLING_HOST))
                    .userIDTypeCode(AuditMessages.userIDTypeCode(callingUserID))
                    .isRequester()
                    .build();
            activeParticipantBuilder[3] = new ActiveParticipantBuilder.Builder(
                    calledUserID,
                    getLocalHostName(auditLogger))
                    .userIDTypeCode(AuditMessages.UserIDTypeCode.URI)
                    .altUserID(AuditLogger.processID())
                    .build();
        }

        return activeParticipantBuilder;
    }

    void spoolProcedureRecord(ProcedureContext ctx) {
        if (ctx.getUnparsedHL7Message() != null)
            return;
        try {
            AuditInfoBuilder info = ctx.getHttpRequest() != null
                    ? buildAuditInfoFORRestful(ctx)
                    : buildAuditInfoForAssociation(ctx);
            AuditServiceUtils.EventType eventType = AuditServiceUtils.EventType.forProcedure(ctx.getEventActionCode());
            writeSpoolFile(eventType, info);
        } catch (Exception e) {
            LOG.warn("Failed to spool Procedure Record : " + e);
        }
    }

    private AuditInfoBuilder buildAuditInfoForAssociation(ProcedureContext ctx) {
        Association as = ctx.getAssociation();
        return new AuditInfoBuilder.Builder()
                .callingHost(ctx.getRemoteHostName())
                .callingUserID(as.getCallingAET())
                .calledUserID(as.getCalledAET())
                .studyUIDAccNumDate(ctx.getAttributes())
                .pIDAndName(ctx.getPatient().getAttributes(), getArchiveDevice())
                .outcome(outcome(ctx.getException()))
                .build();
    }

    private AuditInfoBuilder buildAuditInfoFORRestful(ProcedureContext ctx) {
        HttpServletRequest req  = ctx.getHttpRequest();
        return new AuditInfoBuilder.Builder()
                .callingHost(ctx.getRemoteHostName())
                .callingUserID(KeycloakContext.valueOf(req).getUserName())
                .calledUserID(req.getRequestURI())
                .studyUIDAccNumDate(ctx.getAttributes())
                .pIDAndName(ctx.getPatient().getAttributes(), getArchiveDevice())
                .outcome(outcome(ctx.getException()))
                .build();
    }

    void spoolProcedureRecord(StudyMgtContext ctx) {
        try {
            writeSpoolFile(
                    AuditServiceUtils.EventType.forProcedure(ctx.getEventActionCode()),
                    ctx.getHttpRequest() != null ? restfulTriggeredStudyExpire(ctx) : hl7TriggeredStudyExpire(ctx));
        } catch (Exception e) {
            LOG.warn("Failed to spool Procedure Record : " + e);
        }
    }

    private AuditInfoBuilder hl7TriggeredStudyExpire(StudyMgtContext ctx) {
        HL7Segment msh = ctx.getUnparsedHL7Message().msh();
        return new AuditInfoBuilder.Builder()
                .callingHost(ctx.getRemoteHostName())
                .callingUserID(msh.getSendingApplicationWithFacility())
                .calledUserID(msh.getReceivingApplicationWithFacility())
                .studyUIDAccNumDate(ctx.getAttributes())
                .pIDAndName(ctx.getStudy().getPatient().getAttributes(), getArchiveDevice())
                .outcome(outcome(ctx.getException()))
                .build();
    }

    private AuditInfoBuilder restfulTriggeredStudyExpire(StudyMgtContext ctx) {
        HttpServletRequest request = ctx.getHttpRequest();
        return new AuditInfoBuilder.Builder()
                .callingHost(ctx.getRemoteHostName())
                .callingUserID(KeycloakContext.valueOf(request).getUserName())
                .calledUserID(ctx.getHttpRequest().getRequestURI())
                .studyUIDAccNumDate(ctx.getAttributes())
                .pIDAndName(ctx.getStudy().getPatient().getAttributes(), getArchiveDevice())
                .outcome(outcome(ctx.getException()))
                .build();
    }

    private void auditProcedureRecord(AuditLogger auditLogger, Path path, AuditServiceUtils.EventType et) {
        SpoolFileReader reader = new SpoolFileReader(path.toFile());
        AuditInfo prI = new AuditInfo(reader.getMainInfo());

        EventIdentificationBuilder ei = toBuildEventIdentification(et, prI.getField(AuditInfo.OUTCOME), getEventTime(path, auditLogger));

        ActiveParticipantBuilder[] activeParticipantBuilder = buildProcedureRecordActiveParticipants(auditLogger, prI);
        
        ParticipantObjectDescriptionBuilder desc = new ParticipantObjectDescriptionBuilder.Builder()
                .acc(prI.getField(AuditInfo.ACC_NUM)).build();

        ParticipantObjectIdentificationBuilder poiStudy = new ParticipantObjectIdentificationBuilder.Builder(
                                                        prI.getField(AuditInfo.STUDY_UID),
                                                        AuditMessages.ParticipantObjectIDTypeCode.StudyInstanceUID,
                                                        AuditMessages.ParticipantObjectTypeCode.SystemObject,
                                                        AuditMessages.ParticipantObjectTypeCodeRole.Report)
                                                        .desc(desc)
                                                        .detail(getPod(studyDate, prI.getField(AuditInfo.STUDY_DATE)))
                                                        .detail(getHL7ParticipantObjectDetail(reader))
                                                        .build();
        if (prI.getField(AuditInfo.P_ID) != null)
            emitAuditMessage(auditLogger, ei, activeParticipantBuilder, poiStudy, patientPOI(prI));
        else
            emitAuditMessage(auditLogger, ei, activeParticipantBuilder, poiStudy);
    }

    private ActiveParticipantBuilder[] buildProcedureRecordActiveParticipants(AuditLogger auditLogger, AuditInfo prI) {
        ActiveParticipantBuilder[] activeParticipantBuilder = new ActiveParticipantBuilder[3];
        String archiveUserID = prI.getField(AuditInfo.CALLED_USERID);
        AuditMessages.UserIDTypeCode archiveUserIDTypeCode = archiveUserIDTypeCode(archiveUserID);
        String callingUserID = prI.getField(AuditInfo.CALLING_USERID);
        boolean isHL7Forward = prI.getField(AuditInfo.IS_OUTGOING_HL7) != null;
        activeParticipantBuilder[0] = isHL7Forward
                                ? new ActiveParticipantBuilder.Builder(callingUserID, prI.getField(AuditInfo.CALLING_HOST))
                                    .userIDTypeCode(remoteUserIDTypeCode(archiveUserIDTypeCode, callingUserID)).build()
                                : new ActiveParticipantBuilder.Builder(callingUserID, prI.getField(AuditInfo.CALLING_HOST))
                                    .userIDTypeCode(remoteUserIDTypeCode(archiveUserIDTypeCode, callingUserID))
                                    .isRequester().build();
        activeParticipantBuilder[1] = new ActiveParticipantBuilder.Builder(
                                archiveUserID,
                                getLocalHostName(auditLogger))
                                .userIDTypeCode(archiveUserIDTypeCode)
                                .altUserID(AuditLogger.processID())
                                .build();
        if (isHL7Forward)
            activeParticipantBuilder[2] = new ActiveParticipantBuilder.Builder(
                    device.getDeviceName(),
                    getLocalHostName(auditLogger))
                    .userIDTypeCode(AuditMessages.UserIDTypeCode.DeviceName)
                    .altUserID(AuditLogger.processID())
                    .isRequester()
                    .build();
        return activeParticipantBuilder;
    }

    void spoolProvideAndRegister(ExportContext ctx) {
        if (ctx.getXDSiManifest() == null)
            return;

        try {
            writeSpoolFile(AuditServiceUtils.EventType.PROV_REGIS,
                    ProvideAndRegisterAuditService.provideRegisterAuditInfo(ctx, getArchiveDevice()));
        } catch (Exception e) {
            LOG.warn("Failed to spool Provide and Register : " + e);
        }
    }

    private void auditProvideAndRegister(AuditLogger auditLogger, Path path, AuditServiceUtils.EventType et) {
        SpoolFileReader reader = new SpoolFileReader(path);
        AuditInfo auditInfo = new AuditInfo(reader.getMainInfo());
        emitAuditMessage(
                ProvideAndRegisterAuditService.provideRegisterAuditMsg(auditInfo, auditLogger, et, getEventTime(path, auditLogger)),
                auditLogger);
    }

    private boolean isServiceUserTriggered(Object val) {
        return val != null;
    }

    void spoolStgCmt(StgCmtContext stgCmtContext) {
        try {
            Attributes eventInfo = stgCmtContext.getEventInfo();
            String studyUID = eventInfo.getStrings(Tag.StudyInstanceUID) != null
                    ? Stream.of(eventInfo.getStrings(Tag.StudyInstanceUID)).collect(Collectors.joining(";"))
                    : getArchiveDevice().auditUnknownStudyInstanceUID();

            spoolFailedStgcmt(stgCmtContext, studyUID);
            spoolSuccessStgcmt(stgCmtContext, studyUID);
        } catch (Exception e) {
            LOG.warn("Failed to spool storage commitment : " + e);
        }
    }

    private void spoolSuccessStgcmt(StgCmtContext stgCmtContext, String studyUID) {
        Sequence success = stgCmtContext.getEventInfo().getSequence(Tag.ReferencedSOPSequence);
        if (success != null && !success.isEmpty()) {
            AuditInfoBuilder[] auditInfoBuilder = new AuditInfoBuilder[success.size()+1];
            auditInfoBuilder[0] = new AuditInfoBuilder.Builder()
                                .callingUserID(storageCmtCallingAET(stgCmtContext))
                                .callingHost(storageCmtCallingHost(stgCmtContext))
                                .calledUserID(storageCmtCalledAET(stgCmtContext))
                                .pIDAndName(stgCmtContext.getEventInfo(), getArchiveDevice())
                                .studyUID(studyUID)
                                .build();
            for (int i = 1; i <= success.size(); i++)
                auditInfoBuilder[i] = buildRefSopAuditInfo(success.get(i-1));

            writeSpoolFile(AuditServiceUtils.EventType.STG_COMMIT, auditInfoBuilder);
        }
    }

    private void spoolFailedStgcmt(StgCmtContext stgCmtContext, String studyUID) {
        Sequence failed = stgCmtContext.getEventInfo().getSequence(Tag.FailedSOPSequence);
        if (failed != null && !failed.isEmpty()) {
            AuditInfoBuilder[] auditInfoBuilder = new AuditInfoBuilder[failed.size()+1];
            Set<String> failureReasons = new HashSet<>();
            for (int i = 1; i <= failed.size(); i++) {
                Attributes item = failed.get(i-1);
                auditInfoBuilder[i] = buildRefSopAuditInfo(item);
                failureReasons.add(
                        item.getInt(Tag.FailureReason, 0) == Status.NoSuchObjectInstance
                        ? "NoSuchObjectInstance"
                        : item.getInt(Tag.FailureReason, 0) == Status.ClassInstanceConflict
                            ? "ClassInstanceConflict" : "ProcessingFailure");
            }
            auditInfoBuilder[0] = new AuditInfoBuilder.Builder()
                                .callingUserID(storageCmtCallingAET(stgCmtContext))
                                .callingHost(storageCmtCallingHost(stgCmtContext))
                                .calledUserID(storageCmtCalledAET(stgCmtContext))
                                .pIDAndName(stgCmtContext.getEventInfo(), getArchiveDevice())
                                .studyUID(studyUID)
                                .outcome(failureReasons.stream().collect(Collectors.joining(";")))
                                .build();
            writeSpoolFile(AuditServiceUtils.EventType.STG_COMMIT, auditInfoBuilder);
        }
    }

    private AuditInfoBuilder buildRefSopAuditInfo(Attributes item) {
        return new AuditInfoBuilder.Builder()
                .sopCUID(item.getString(Tag.ReferencedSOPClassUID))
                .sopIUID(item.getString(Tag.ReferencedSOPInstanceUID)).build();
    }

    private String storageCmtCallingHost(StgCmtContext stgCmtContext) {
        return stgCmtContext.getRequest() != null
                ? stgCmtContext.getRequest().requesterHost
                : stgCmtContext.getRemoteAE() != null
                    ? stgCmtContext.getRemoteAE().getConnections().get(0).getHostname() : null;
    }

    private String storageCmtCalledAET(StgCmtContext stgCmtContext) {
        return stgCmtContext.getRequest() != null
                ? stgCmtContext.getRequest().requestURI
                : stgCmtContext.getLocalAET();
    }

    private String storageCmtCallingAET(StgCmtContext stgCmtContext) {
        return stgCmtContext.getRequest() != null
                ? stgCmtContext.getRequest().requesterUserID
                : stgCmtContext.getRemoteAE() != null
                    ? stgCmtContext.getRemoteAE().getAETitle() : null;
    }

    private void auditStorageCommit(AuditLogger auditLogger, Path path, AuditServiceUtils.EventType et) {
        SpoolFileReader reader = new SpoolFileReader(path);
        AuditInfo auditInfo = new AuditInfo(reader.getMainInfo());
        EventIdentificationBuilder ei = toBuildEventIdentification(et, auditInfo.getField(AuditInfo.OUTCOME), getEventTime(path, auditLogger));
        ActiveParticipantBuilder[] activeParticipantBuilder = new ActiveParticipantBuilder[2];
        String archiveUserID = auditInfo.getField(AuditInfo.CALLED_USERID);
        AuditMessages.UserIDTypeCode archiveUserIDTypeCode = archiveUserIDTypeCode(archiveUserID);
        activeParticipantBuilder[0] = new ActiveParticipantBuilder.Builder(
                archiveUserID,
                getLocalHostName(auditLogger))
                .userIDTypeCode(archiveUserIDTypeCode)
                .altUserID(AuditLogger.processID())
                .roleIDCode(et.destination).build();
        String callingUserID = auditInfo.getField(AuditInfo.CALLING_USERID);
        if (callingUserID != null)
            activeParticipantBuilder[1] = new ActiveParticipantBuilder.Builder(
                                            callingUserID,
                                            auditInfo.getField(AuditInfo.CALLING_HOST))
                                            .userIDTypeCode(remoteUserIDTypeCode(archiveUserIDTypeCode, callingUserID))
                                            .isRequester()
                                            .roleIDCode(et.source).build();
       
        String[] studyUIDs = StringUtils.split(auditInfo.getField(AuditInfo.STUDY_UID), ';');

        ParticipantObjectDescriptionBuilder poDesc = new ParticipantObjectDescriptionBuilder.Builder()
                .sopC(toSOPClasses(buildSOPClassMap(reader), studyUIDs.length > 1 || auditInfo.getField(AuditInfo.OUTCOME) != null))
                .pocsStudyUIDs(studyUIDs).build();
        
        ParticipantObjectIdentificationBuilder poiStudy = new ParticipantObjectIdentificationBuilder.Builder(studyUIDs[0],
                AuditMessages.ParticipantObjectIDTypeCode.StudyInstanceUID,
                AuditMessages.ParticipantObjectTypeCode.SystemObject, AuditMessages.ParticipantObjectTypeCodeRole.Report)
                .desc(poDesc).lifeCycle(AuditMessages.ParticipantObjectDataLifeCycle.Verification).build();
        emitAuditMessage(auditLogger, ei, activeParticipantBuilder, poiStudy, patientPOI(auditInfo));
    }

    private SOPClass[] toSOPClasses(HashMap<String, HashSet<String>> sopClassMap, boolean showIUID) {
        SOPClass[] sopClasses = new SOPClass[sopClassMap.size()];
        int count = 0;
        for (Map.Entry<String, HashSet<String>> entry : sopClassMap.entrySet()) {
            sopClasses[count] = AuditMessages.createSOPClass(
                    showIUID ? entry.getValue() : null,
                    entry.getKey(),
                    entry.getValue().size());
            count++;
        }
        return sopClasses;
    }

    static SOPClass[] toSOPClasses1(HashMap<String, HashSet<String>> sopClassMap, boolean showIUID) {
        SOPClass[] sopClasses = new SOPClass[sopClassMap.size()];
        int count = 0;
        for (Map.Entry<String, HashSet<String>> entry : sopClassMap.entrySet()) {
            sopClasses[count] = AuditMessages.createSOPClass(
                    showIUID ? entry.getValue() : null,
                    entry.getKey(),
                    entry.getValue().size());
            count++;
        }
        return sopClasses;
    }

    static SOPClass[] toSOPClasses(SpoolFileReader reader, boolean showIUID) {
        HashMap<String, HashSet<String>> sopClassMap = sopClassMap(reader);
        SOPClass[] sopClasses = new SOPClass[sopClassMap.size()];
        int count = 0;
        for (Map.Entry<String, HashSet<String>> entry : sopClassMap.entrySet()) {
            sopClasses[count] = AuditMessages.createSOPClass(
                    showIUID ? entry.getValue() : null,
                    entry.getKey(),
                    entry.getValue().size());
            count++;
        }
        return sopClasses;
    }

    private static HashMap<String, HashSet<String>> sopClassMap(SpoolFileReader reader) {
        HashMap<String, HashSet<String>> sopClassMap = new HashMap<>();
        for (String line : reader.getInstanceLines()) {
            AuditInfo ii = new AuditInfo(line);
            sopClassMap.computeIfAbsent(ii.getField(AuditInfo.SOP_CUID), k -> new HashSet<>()).add(ii.getField(AuditInfo.SOP_IUID));
        }
        return sopClassMap;
    }

    void spoolAssociationFailure(AssociationEvent associationEvent) {
        try {
            writeSpoolFile(
                    AuditServiceUtils.EventType.ASSOC_FAIL,
                    AssociationEventsAuditService.associationFailureAuditInfo(associationEvent));
        } catch (Exception e) {
            LOG.warn("Failed to spool association event failure : " + e);
        }
    }

    private void auditAssociationFailure(AuditLogger auditLogger, Path path, AuditServiceUtils.EventType et) {
        SpoolFileReader reader = new SpoolFileReader(path);
        AuditInfo auditInfo = new AuditInfo(reader.getMainInfo());
        emitAuditMessage(
                AssociationEventsAuditService.associationFailureAuditMsg(auditInfo, et, getEventTime(path, auditLogger)),
                auditLogger);
    }

    private String outcome(Exception e) {
        return e != null ? e.getMessage() : null;
    }

    private ParticipantObjectDetail getPod(String type, String value) {
        return AuditMessages.createParticipantObjectDetail(type, value);
    }

    private Calendar getEventTime(Path path, AuditLogger auditLogger){
        Calendar eventTime = auditLogger.timeStamp();
        try {
            eventTime.setTimeInMillis(Files.getLastModifiedTime(path).toMillis());
        } catch (Exception e) {
            LOG.warn("Failed to get Last Modified Time of Audit Spool File - {} ", auditLogger.getCommonName(), path, e);
        }
        return eventTime;
    }

    private String getLocalHostName(AuditLogger log) {
        return log.getConnections().get(0).getHostname();
    }

    private Path toDirPath(AuditLogger auditLogger) {
        return Paths.get(
                StringUtils.replaceSystemProperties(getArchiveDevice().getAuditSpoolDirectory()),
                auditLogger.getCommonName().replaceAll(" ", "_"));
    }

    private void writeSpoolFile(AuditInfoBuilder auditInfoBuilder, String data) {
        if (auditInfoBuilder == null) {
            LOG.warn("Attempt to write empty file : ", AuditServiceUtils.EventType.LDAP_CHNGS);
            return;
        }
        FileTime eventTime = null;
        boolean auditAggregate = getArchiveDevice().isAuditAggregate();
        AuditLoggerDeviceExtension ext = device.getDeviceExtension(AuditLoggerDeviceExtension.class);
        for (AuditLogger auditLogger : ext.getAuditLoggers()) {
            if (auditLogger.isInstalled()) {
                Path dir = toDirPath(auditLogger);
                try {
                    Files.createDirectories(dir);
                    Path file = Files.createTempFile(dir, AuditServiceUtils.EventType.LDAP_CHNGS.name(), null);
                    try (SpoolFileWriter writer = new SpoolFileWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                            StandardOpenOption.APPEND))) {
                        writer.writeLine(new AuditInfo(auditInfoBuilder), data);
                    }
                    if (eventTime == null)
                        eventTime = Files.getLastModifiedTime(file);
                    else
                        Files.setLastModifiedTime(file, eventTime);
                    if (!auditAggregate)
                        auditAndProcessFile(auditLogger, file);
                } catch (Exception e) {
                    LOG.warn("Failed to write to Audit Spool File - {} ", auditLogger.getCommonName(), e);
                }
            }
        }
    }

    private void writeSpoolFile(
            AuditServiceUtils.EventType eventType, AuditInfoBuilder auditInfoBuilder, byte[] data, byte[] ack) {
        if (auditInfoBuilder == null) {
            LOG.warn("Attempt to write empty file : ", eventType);
            return;
        }
        FileTime eventTime = null;
        boolean auditAggregate = getArchiveDevice().isAuditAggregate();
        AuditLoggerDeviceExtension ext = device.getDeviceExtension(AuditLoggerDeviceExtension.class);
        for (AuditLogger auditLogger : ext.getAuditLoggers()) {
            if (auditLogger.isInstalled()) {
                Path dir = toDirPath(auditLogger);
                try {
                    Files.createDirectories(dir);
                    Path file = Files.createTempFile(dir, String.valueOf(eventType), null);
                    try (BufferedOutputStream out = new BufferedOutputStream(
                            Files.newOutputStream(file, StandardOpenOption.APPEND))) {
                        out.write(data);
                        if (ack.length > 0)
                            out.write(ack);
                        try (SpoolFileWriter writer = new SpoolFileWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                                StandardOpenOption.APPEND))) {
                            writer.writeLine(new AuditInfo(auditInfoBuilder));
                        }
                    }
                    if (eventTime == null)
                        eventTime = Files.getLastModifiedTime(file);
                    else
                        Files.setLastModifiedTime(file, eventTime);
                    if (!auditAggregate)
                        auditAndProcessFile(auditLogger, file);
                } catch (Exception e) {
                    LOG.warn("Failed to write to Audit Spool File - {} ", auditLogger.getCommonName(), e);
                }
            }
        }
    }

    private void writeSpoolFile(AuditServiceUtils.EventType eventType, AuditInfoBuilder... auditInfoBuilders) {
        if (auditInfoBuilders == null) {
            LOG.warn("Attempt to write empty file : ", eventType);
            return;
        }
        FileTime eventTime = null;
        boolean auditAggregate = getArchiveDevice().isAuditAggregate();
        AuditLoggerDeviceExtension ext = device.getDeviceExtension(AuditLoggerDeviceExtension.class);
        for (AuditLogger auditLogger : ext.getAuditLoggers()) {
            if (auditLogger.isInstalled()) {
                Path dir = toDirPath(auditLogger);
                try {
                    Files.createDirectories(dir);
                    Path file = Files.createTempFile(dir, String.valueOf(eventType), null);
                    try (SpoolFileWriter writer = new SpoolFileWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                            StandardOpenOption.APPEND))) {
                        for (AuditInfoBuilder auditInfoBuilder : auditInfoBuilders)
                            writer.writeLine(new AuditInfo(auditInfoBuilder));
                    }
                    if (eventTime == null)
                        eventTime = Files.getLastModifiedTime(file);
                    else
                        Files.setLastModifiedTime(file, eventTime);
                    if (!auditAggregate)
                        auditAndProcessFile(auditLogger, file);
                } catch (Exception e) {
                    LOG.warn("Failed to write to Audit Spool File - {} ", auditLogger.getCommonName(), e);
                }
            }
        }
    }

    private void writeSpoolFileStoreOrWadoRetrieve(String fileName, AuditInfoBuilder patStudyInfo, AuditInfoBuilder instanceInfo) {
        if (patStudyInfo == null && instanceInfo == null) {
            LOG.warn("Attempt to write empty file : " + fileName);
            return;
        }
        FileTime eventTime = null;
        boolean auditAggregate = getArchiveDevice().isAuditAggregate();
        AuditLoggerDeviceExtension ext = device.getDeviceExtension(AuditLoggerDeviceExtension.class);
        for (AuditLogger auditLogger : ext.getAuditLoggers()) {
            if (auditLogger.isInstalled()) {
                Path dir = toDirPath(auditLogger);
                Path file = dir.resolve(fileName);
                boolean append = Files.exists(file);
                try {
                    if (!append)
                        Files.createDirectories(dir);
                    try (SpoolFileWriter writer = new SpoolFileWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                            append ? StandardOpenOption.APPEND : StandardOpenOption.CREATE_NEW))) {
                        if (!append) {
                            writer.writeLine(new AuditInfo(patStudyInfo));
                        }
                        writer.writeLine(new AuditInfo(instanceInfo));
                    }
                    if (eventTime == null)
                        eventTime = Files.getLastModifiedTime(file);
                    else
                        Files.setLastModifiedTime(file, eventTime);
                    if (!auditAggregate)
                        auditAndProcessFile(auditLogger, file);
                } catch (Exception e) {
                    LOG.warn("Failed to write to Audit Spool File - {} ", auditLogger.getCommonName(), file, e);
                }
            }
        }
    }

    private void emitAuditMessage(
            AuditLogger logger, EventIdentificationBuilder eventIdentificationBuilder, ActiveParticipantBuilder[] activeParticipantBuilder,
            ParticipantObjectIdentificationBuilder... participantObjectIdentificationBuilder) {
        AuditMessage msg = AuditMessages.createMessage(eventIdentificationBuilder, activeParticipantBuilder, participantObjectIdentificationBuilder);
        msg.getAuditSourceIdentification().add(logger.createAuditSourceIdentification());
        try {
            logger.write(logger.timeStamp(), msg);
        } catch (Exception e) {
            LOG.warn("Failed to emit audit message", logger.getCommonName(), e);
        }
    }

    private void emitAuditMessage(AuditMessage msg, AuditLogger logger) {
        msg.getAuditSourceIdentification().add(logger.createAuditSourceIdentification());
        try {
            logger.write(logger.timeStamp(), msg);
        } catch (Exception e) {
            LOG.warn("Failed to emit audit message", logger.getCommonName(), e);
        }
    }

    private EventIdentificationBuilder toCustomBuildEventIdentification(AuditServiceUtils.EventType et, String failureDesc, String warningDesc, Calendar t) {
        return failureDesc != null
                ? toBuildEventIdentification(et, failureDesc, t)
                : new EventIdentificationBuilder.Builder(
                    et.eventID, et.eventActionCode, t, AuditMessages.EventOutcomeIndicator.Success)
                    .outcomeDesc(warningDesc).build();
    }

    private EventIdentificationBuilder toBuildEventIdentification(AuditServiceUtils.EventType et, String desc, Calendar t) {
        return new EventIdentificationBuilder.Builder(
                et.eventID, et.eventActionCode, t,
                desc != null ? AuditMessages.EventOutcomeIndicator.MinorFailure : AuditMessages.EventOutcomeIndicator.Success)
                .outcomeDesc(desc).eventTypeCode(et.eventTypeCode).build();
    }
    
    private ParticipantObjectIdentificationBuilder patientPOI(AuditInfo auditInfo) {
        return new ParticipantObjectIdentificationBuilder.Builder(
                auditInfo.getField(AuditInfo.P_ID),
                AuditMessages.ParticipantObjectIDTypeCode.PatientNumber,
                AuditMessages.ParticipantObjectTypeCode.Person,
                AuditMessages.ParticipantObjectTypeCodeRole.Patient)
                .name(auditInfo.getField(AuditInfo.P_NAME))
                .build();
    }

    private AuditMessages.UserIDTypeCode archiveUserIDTypeCode(String userID) {
        return  userID.indexOf('/') != -1
                ? AuditMessages.UserIDTypeCode.URI
                : userID.indexOf('|') != -1
                    ? AuditMessages.UserIDTypeCode.ApplicationFacility
                    : userID.equals(device.getDeviceName())
                        ? AuditMessages.UserIDTypeCode.DeviceName
                        : AuditMessages.UserIDTypeCode.StationAETitle;
    }

    static AuditMessages.UserIDTypeCode remoteUserIDTypeCode(
            AuditMessages.UserIDTypeCode archiveUserIDTypeCode, String remoteUserID) {
        if (remoteUserID != null)
            return remoteUserID.indexOf('|') != -1
                ? AuditMessages.UserIDTypeCode.ApplicationFacility
                : archiveUserIDTypeCode == AuditMessages.UserIDTypeCode.URI
                    ? AuditMessages.userIDTypeCode(remoteUserID)
                    : AuditMessages.UserIDTypeCode.StationAETitle;

        LOG.warn("Remote user ID was not set during spooling.");
        return null;
    }
}