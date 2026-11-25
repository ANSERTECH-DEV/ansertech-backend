package com.ansertech.service.email;

import java.util.List;

public interface EmailPollingService {
    List<RawEmailData> fetchUnreadEmails(int maxResults) throws Exception;
    void markAsRead(String messageId) throws Exception;
    byte[] fetchAttachment(String messageId, String attachmentId) throws Exception;

    record RawEmailData(
            String externalId,
            String senderEmail,
            String senderName,
            String subject,
            String body,
            java.time.LocalDateTime receivedAt,
            boolean hasAttachments,
            List<AttachmentInfo> attachments,
            List<String> ccAddresses
    ) {}

    /**
     * @param inline true si Outlook lo marca como incrustado en el cuerpo (p. ej. el logo de la firma).
     *               En attachments_json antiguos no existe y se lee como false.
     */
    record AttachmentInfo(
            String id,
            String name,
            String mimeType,
            long size,
            boolean inline
    ) {}
}
