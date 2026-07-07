package com.gamma.connect.notify;

import com.gamma.notify.Notification;
import org.junit.jupiter.api.Test;

import javax.mail.Message;
import javax.mail.internet.MimeMessage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SmtpEmailChannel} message construction and the {@code configured()} gate. Actual SMTP transport
 * is javax.mail's; what is ours — configuration wiring, addressing, subject/body rendering — is asserted
 * on the built {@link MimeMessage} without a live server.
 */
class SmtpEmailChannelTest {

    private static Notification sample() {
        return Notification.create("ops", "ALERT_FIRED", "evt-1",
                "Alert: reject-rate-high", "reject_rate 0.4 breached threshold 0.1", "alert:orders:reject-rate-high");
    }

    @Test
    void buildsAddressedPlainTextMail() throws Exception {
        SmtpEmailChannel ch = new SmtpEmailChannel("mail.example.com", 587,
                "inspecto@example.com", "ops@example.com", null, null, true);
        assertTrue(ch.configured());

        MimeMessage m = ch.message(sample());
        assertEquals("[Inspecto] Alert: reject-rate-high", m.getSubject());
        assertEquals("inspecto@example.com", m.getFrom()[0].toString());
        assertEquals("ops@example.com", m.getRecipients(Message.RecipientType.TO)[0].toString());
        String body = (String) m.getContent();
        assertTrue(body.contains("reject_rate 0.4 breached threshold 0.1"));
        assertTrue(body.contains("category: ops"));
        assertEquals("587", m.getSession().getProperty("mail.smtp.port"));
        assertEquals("true", m.getSession().getProperty("mail.smtp.starttls.enable"));
    }

    @Test
    void defaultsFromAddressAndSupportsMultipleRecipients() throws Exception {
        SmtpEmailChannel ch = new SmtpEmailChannel("mail.example.com", 25,
                null, "a@example.com, b@example.com", null, null, false);
        MimeMessage m = ch.message(sample());
        assertEquals("inspecto@mail.example.com", m.getFrom()[0].toString());
        assertEquals(2, m.getRecipients(Message.RecipientType.TO).length);
        assertNull(m.getSession().getProperty("mail.smtp.starttls.enable"));
    }

    @Test
    void unconfiguredWithoutHostOrRecipient() {
        assertFalse(new SmtpEmailChannel(null, 25, null, "ops@example.com", null, null, false).configured());
        assertFalse(new SmtpEmailChannel("mail.example.com", 25, null, null, null, null, false).configured());
        assertEquals("email", new SmtpEmailChannel(null, 25, null, null, null, null, false).id());
    }

    @Test
    void authEnabledOnlyWhenCredentialsPresent() throws Exception {
        SmtpEmailChannel auth = new SmtpEmailChannel("h", 25, "f@x.com", "t@x.com", "user", "pw", false);
        assertEquals("true", auth.message(sample()).getSession().getProperty("mail.smtp.auth"));

        SmtpEmailChannel anon = new SmtpEmailChannel("h", 25, "f@x.com", "t@x.com", null, null, false);
        assertNull(anon.message(sample()).getSession().getProperty("mail.smtp.auth"));
    }
}
