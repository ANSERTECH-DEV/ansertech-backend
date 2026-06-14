package com.ansertech.service.email;

import com.microsoft.graph.models.Attachment;
import com.microsoft.graph.models.FileAttachment;
import com.microsoft.graph.models.Message;
import com.microsoft.graph.models.MessageCollectionResponse;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GraphPollingService implements EmailPollingService {

    private final GraphServiceClient graphClient;

    @Override
    public List<RawEmailData> fetchUnreadEmails(int maxResults) throws Exception {
        log.info("Polling unread emails from the authenticated mailbox");
        List<RawEmailData> results = new ArrayList<>();

        MessageCollectionResponse response = graphClient.me()
                .mailFolders().byMailFolderId("inbox")
                .messages()
                .get(requestConfiguration -> {
                    requestConfiguration.queryParameters.filter = "isRead eq false";
                    requestConfiguration.queryParameters.top = maxResults;
                    requestConfiguration.queryParameters.expand = new String[]{"attachments"};
                });

        if (response != null && response.getValue() != null) {
            for (Message msg : response.getValue()) {
                results.add(mapToRawEmailData(msg));
            }
        }
        return results;
    }

    private RawEmailData mapToRawEmailData(Message msg) {
        String uid = msg.getId();
        String senderEmail = "";
        String senderName = "";

        if (msg.getFrom() != null && msg.getFrom().getEmailAddress() != null) {
            senderEmail = Optional.ofNullable(msg.getFrom().getEmailAddress().getAddress()).orElse("");
            senderName = Optional.ofNullable(msg.getFrom().getEmailAddress().getName()).orElse("");
        }

        String subject = msg.getSubject() != null ? msg.getSubject() : "(sin asunto)";

        LocalDateTime receivedAt = LocalDateTime.now();
        if (msg.getReceivedDateTime() != null) {
            receivedAt = msg.getReceivedDateTime().toZonedDateTime().withZoneSameInstant(ZoneId.of("America/Lima")).toLocalDateTime();
        }

        String body = "";
        if (msg.getBody() != null && msg.getBody().getContent() != null) {
            body = msg.getBody().getContent();
        }

        List<AttachmentInfo> attachments = new ArrayList<>();
        boolean hasAttachments = msg.getHasAttachments() != null && msg.getHasAttachments();

        if (msg.getAttachments() != null) {
            for (Attachment attachment : msg.getAttachments()) {
                attachments.add(new AttachmentInfo(
                        attachment.getId(),
                        attachment.getName(),
                        attachment.getContentType(),
                        attachment.getSize() != null ? attachment.getSize().longValue() : 0L
                ));
            }
        }

        return new RawEmailData(uid, senderEmail, senderName, subject, body, receivedAt, hasAttachments, attachments);
    }

    @Override
    public void markAsRead(String messageId) throws Exception {
        Message message = new Message();
        message.setIsRead(true);

        graphClient.me()
                .messages().byMessageId(messageId)
                .patch(message);
    }

    @Override
    public byte[] fetchAttachment(String messageId, String attachmentId) throws Exception {
        Attachment attachment = graphClient.me()
                .messages().byMessageId(messageId)
                .attachments().byAttachmentId(attachmentId)
                .get();

        if (attachment instanceof FileAttachment fileAttachment) {
            byte[] contentBytes = fileAttachment.getContentBytes();
            if (contentBytes != null) {
                return contentBytes;
            }
        }
        return new byte[0];
    }
}
