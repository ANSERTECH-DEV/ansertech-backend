package com.ansertech.service.email;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.FlagTerm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

@Slf4j
@Service
public class ImapPollingService implements EmailPollingService {

    @Value("${email.imap.host:imap.gmail.com}")
    private String host;

    @Value("${email.imap.port:993}")
    private int port;

    @Value("${email.imap.username:}")
    private String username;

    @Value("${email.imap.password:}")
    private String password;

    private Store connectStore() throws MessagingException {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", host);
        props.put("mail.imaps.port", String.valueOf(port));
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.connectiontimeout", "10000");
        props.put("mail.imaps.timeout", "10000");
        Session session = Session.getInstance(props);
        Store store = session.getStore("imaps");
        store.connect(host, username, password);
        return store;
    }

    @Override
    public List<RawEmailData> fetchUnreadEmails(int maxResults) throws Exception {
        List<RawEmailData> result = new ArrayList<>();
        Store store = null;
        Folder folder = null;
        try {
            store = connectStore();
            folder = store.getFolder("INBOX");
            folder.open(Folder.READ_ONLY);

            Message[] messages = folder.search(
                    new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            int count = Math.min(messages.length, maxResults);

            for (int i = 0; i < count; i++) {
                try {
                    result.add(parseMessage(folder, messages[i]));
                } catch (Exception e) {
                    log.warn("Error procesando mensaje IMAP: {}", e.getMessage());
                }
            }
        } finally {
            closeQuietly(folder, store);
        }
        return result;
    }

    private RawEmailData parseMessage(Folder folder, Message msg) throws Exception {
        String uid = resolveUid(folder, msg);

        String senderEmail = "";
        String senderName = "";
        Address[] from = msg.getFrom();
        if (from != null && from.length > 0 && from[0] instanceof InternetAddress ia) {
            senderEmail = Optional.ofNullable(ia.getAddress()).orElse("");
            senderName = Optional.ofNullable(ia.getPersonal()).orElse("");
        }

        String subject = Optional.ofNullable(msg.getSubject()).orElse("(sin asunto)");
        LocalDateTime receivedAt = msg.getReceivedDate() != null
                ? msg.getReceivedDate().toInstant().atZone(ZoneId.of("America/Lima")).toLocalDateTime()
                : LocalDateTime.now();

        List<AttachmentInfo> attachments = new ArrayList<>();
        String body = extractContent(msg, attachments);

        return new RawEmailData(uid, senderEmail, senderName, subject,
                body, receivedAt, !attachments.isEmpty(), attachments);
    }

    private String extractContent(Part part, List<AttachmentInfo> attachments) throws Exception {
        if (part.getContent() instanceof Multipart mp) {
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                String disp = bp.getDisposition();
                if (Part.ATTACHMENT.equalsIgnoreCase(disp) || bp.getFileName() != null) {
                    String fname = Optional.ofNullable(bp.getFileName()).orElse("attachment");
                    attachments.add(new AttachmentInfo(
                            String.valueOf(attachments.size()),
                            fname,
                            bp.getContentType().split(";")[0].trim().toLowerCase(),
                            Math.max(bp.getSize(), 0)
                    ));
                } else {
                    String sub = extractContent(bp, attachments);
                    if (!sub.isBlank() && body.isEmpty()) {
                        body.append(sub);
                    }
                }
            }
            return body.toString();
        }

        String contentType = part.getContentType() != null ? part.getContentType().toLowerCase() : "";
        if (contentType.startsWith("text/plain") || contentType.startsWith("text/html")) {
            Object content = part.getContent();
            return content instanceof String s ? s : "";
        }
        return "";
    }

    @Override
    public void markAsRead(String messageId) throws Exception {
        Store store = null;
        Folder folder = null;
        try {
            store = connectStore();
            folder = store.getFolder("INBOX");
            folder.open(Folder.READ_WRITE);
            Message msg = findMessage(folder, messageId);
            if (msg != null) {
                msg.setFlag(Flags.Flag.SEEN, true);
            }
        } finally {
            closeQuietly(folder, store);
        }
    }

    @Override
    public byte[] fetchAttachment(String messageId, String attachmentId) throws Exception {
        Store store = null;
        Folder folder = null;
        try {
            store = connectStore();
            folder = store.getFolder("INBOX");
            folder.open(Folder.READ_ONLY);
            Message msg = findMessage(folder, messageId);
            if (msg == null) return new byte[0];

            int targetIndex = Integer.parseInt(attachmentId);
            int[] counter = {0};
            byte[] bytes = findAttachmentBytes(msg, counter, targetIndex);
            return bytes != null ? bytes : new byte[0];
        } finally {
            closeQuietly(folder, store);
        }
    }

    private byte[] findAttachmentBytes(Part part, int[] counter, int targetIndex) throws Exception {
        if (part.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                String disp = bp.getDisposition();
                if (Part.ATTACHMENT.equalsIgnoreCase(disp) || bp.getFileName() != null) {
                    if (counter[0] == targetIndex) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        bp.getInputStream().transferTo(baos);
                        return baos.toByteArray();
                    }
                    counter[0]++;
                } else {
                    byte[] nested = findAttachmentBytes(bp, counter, targetIndex);
                    if (nested != null) return nested;
                }
            }
        }
        return null;
    }

    private String resolveUid(Folder folder, Message msg) {
        try {
            if (folder instanceof UIDFolder uf) {
                return String.valueOf(uf.getUID(msg));
            }
        } catch (MessagingException ignored) {}
        return String.valueOf(msg.getMessageNumber());
    }

    private Message findMessage(Folder folder, String uid) {
        try {
            if (folder instanceof UIDFolder uf) {
                return uf.getMessageByUID(Long.parseLong(uid));
            }
            return folder.getMessage(Integer.parseInt(uid));
        } catch (Exception e) {
            log.warn("No se encontró mensaje con UID {}: {}", uid, e.getMessage());
            return null;
        }
    }

    private void closeQuietly(Folder folder, Store store) {
        try { if (folder != null && folder.isOpen()) folder.close(false); } catch (Exception ignored) {}
        try { if (store != null && store.isConnected()) store.close(); } catch (Exception ignored) {}
    }
}
