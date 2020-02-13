package org.nrg.containers.jms.errors;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.nrg.mail.services.MailService;
import org.nrg.xdat.preferences.SiteConfigPreferences;
import org.springframework.util.ErrorHandler;

import javax.mail.MessagingException;

@Slf4j
public class ContainerJmsErrorHandler implements ErrorHandler {
    private final SiteConfigPreferences siteConfigPreferences;
    private final MailService mailService;

    public ContainerJmsErrorHandler(final SiteConfigPreferences siteConfigPreferences,
                                    final MailService mailService) {
        this.siteConfigPreferences = siteConfigPreferences;
        this.mailService = mailService;
    }

    @Override
    public void handleError(Throwable t) {
        log.error("JMS error", t);
        String adminEmail = siteConfigPreferences.getAdminEmail();
        if (StringUtils.isBlank(adminEmail)) {
            return;
        }
        String siteId = siteConfigPreferences.getSiteId();
        String sb = "<html>" +
                "<body>" +
                "<p>Dear " + siteId + " admin,</p>" +
                "<p>Your XNAT instance " + siteId + " (" + siteConfigPreferences.getSiteUrl()+ ") threw an " +
                "exception during JMS processing. Details follow.</p>" +
                "<p><strong>" + t.getClass() + "</strong> " + t.getMessage() + "</p>" +
                "<pre>" + ExceptionUtils.getStackTrace(t) + "</pre>"+
                "</body>" +
                "</html>";
        try {
            mailService.sendHtmlMessage(adminEmail, adminEmail, siteId + " JMS Error", sb);
        } catch (MessagingException e) {
            log.error("Unable to send email", e);
        }
    }
}
