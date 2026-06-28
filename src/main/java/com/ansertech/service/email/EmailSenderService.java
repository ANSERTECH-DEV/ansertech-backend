package com.ansertech.service.email;

import com.microsoft.graph.models.*;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.users.item.sendmail.SendMailPostRequestBody;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailSenderService {

    private final GraphServiceClient graphClient;

    public void sendQuotationEmail(String toEmail, String toName,
                                   String quotationNumber,
                                   BigDecimal subtotal, BigDecimal igv, BigDecimal total,
                                   byte[] pdfBytes, List<String> ccAddresses) {
        ItemBody body = new ItemBody();
        body.setContentType(BodyType.Html);
        body.setContent(buildHtmlBody(toName, quotationNumber, subtotal, igv, total));

        EmailAddress emailAddress = new EmailAddress();
        emailAddress.setAddress(toEmail);
        emailAddress.setName(toName != null ? toName : toEmail);

        Recipient recipient = new Recipient();
        recipient.setEmailAddress(emailAddress);

        FileAttachment attachment = new FileAttachment();
        attachment.setOdataType("#microsoft.graph.fileAttachment");
        attachment.setName(quotationNumber + ".pdf");
        attachment.setContentType("application/pdf");
        attachment.setContentBytes(pdfBytes);

        Message message = new Message();
        message.setSubject("Cotización " + quotationNumber + " - Ansertech Perú S.A.C.");
        message.setBody(body);
        message.setToRecipients(List.of(recipient));
        message.setAttachments(List.of(attachment));

        if (ccAddresses != null && !ccAddresses.isEmpty()) {
            List<Recipient> ccList = ccAddresses.stream()
                    .filter(addr -> addr != null && !addr.isBlank())
                    .map(addr -> {
                        EmailAddress cc = new EmailAddress();
                        cc.setAddress(addr.trim());
                        Recipient r = new Recipient();
                        r.setEmailAddress(cc);
                        return r;
                    }).toList();
            if (!ccList.isEmpty()) {
                message.setCcRecipients(ccList);
                log.info("CC agregado: {}", ccAddresses);
            }
        }

        SendMailPostRequestBody sendMailBody = new SendMailPostRequestBody();
        sendMailBody.setMessage(message);
        sendMailBody.setSaveToSentItems(true);

        graphClient.me().sendMail().post(sendMailBody);
        log.info("Cotización {} enviada a {}", quotationNumber, toEmail);
    }

    private String buildHtmlBody(String name, String quotationNumber,
                                  BigDecimal subtotal, BigDecimal igv, BigDecimal total) {
        String greeting = (name != null && !name.isBlank()) ? name : "Cliente";

        return String.format("""
            <html><body style="font-family:Arial,sans-serif;color:#333;margin:0;padding:0;">
            <div style="max-width:600px;margin:auto;">
              <div style="background:#1a2a4a;padding:24px;text-align:center;">
                <h2 style="color:#fff;margin:0;font-size:20px;">ANSERTECH PERÚ S.A.C.</h2>
                <p style="color:#3a8fd1;margin:4px 0;font-size:13px;">RUC: 20600293321</p>
              </div>
              <div style="padding:28px 32px;">
                <p>Estimado/a <strong>%s</strong>,</p>
                <p>Adjunto encontrará la cotización <strong>%s</strong>
                   elaborada por nuestro equipo comercial en respuesta a su solicitud.</p>
                <table style="width:100%%;border-collapse:collapse;margin:20px 0;">
                  <tr style="background:#f4f6fa;">
                    <td style="padding:10px 14px;border-bottom:1px solid #e0e0e0;">Subtotal</td>
                    <td style="padding:10px 14px;text-align:right;border-bottom:1px solid #e0e0e0;">
                      S/ %,.2f</td>
                  </tr>
                  <tr>
                    <td style="padding:10px 14px;border-bottom:1px solid #e0e0e0;">IGV (18%%)</td>
                    <td style="padding:10px 14px;text-align:right;border-bottom:1px solid #e0e0e0;">
                      S/ %,.2f</td>
                  </tr>
                  <tr style="background:#1a2a4a;color:#fff;">
                    <td style="padding:12px 14px;font-weight:bold;">TOTAL PEN</td>
                    <td style="padding:12px 14px;text-align:right;font-weight:bold;color:#3a8fd1;">
                      S/ %,.2f</td>
                  </tr>
                </table>
                <p style="color:#888;font-size:12px;">
                  Esta cotización es válida por 15 días desde su emisión.<br/>
                  Precios incluyen IGV. Sujeto a disponibilidad de stock.
                </p>
                <p>Atentamente,<br/>
                   <strong>Ansertech Perú S.A.C.</strong><br/>
                   <span style="color:#888;font-size:12px;">Lima, Perú</span>
                </p>
              </div>
              <div style="background:#f4f6fa;padding:12px;text-align:center;
                          font-size:11px;color:#aaa;">
                Ansertech Perú S.A.C. — RUC 20600293321
              </div>
            </div>
            </body></html>
            """, greeting, quotationNumber, subtotal, igv, total);
    }
}
