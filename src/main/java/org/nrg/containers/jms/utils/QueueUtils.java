package org.nrg.containers.jms.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.nrg.xdat.XDAT;
import org.springframework.jms.core.BrowserCallback;
import org.springframework.jms.core.JmsTemplate;

import javax.jms.Destination;
import javax.jms.Message;
import java.util.Enumeration;

@Slf4j
public class QueueUtils {
    private static final BrowserCallback<Integer> MESSAGE_COUNTER_CALLBACK = (session, browser) -> {
        final String queueName = browser.getQueue().getQueueName();

        final Enumeration<?> enumeration = browser.getEnumeration();
        if (enumeration == null) {
            log.info("No messages found on queue {}", queueName);
            return 0;
        }
        int counter = 0;
        while (enumeration.hasMoreElements()) {
            final Message message = (Message) enumeration.nextElement();
            log.trace("Found queue {} message #{}: {}", queueName, ++counter, message);
        }
        if (counter == 0) {
            log.info("No messages found on queue {}", queueName);
        }
        return counter;
    };

	/*
	 * Get the count of the current messages in this queue.
	 */
    public static int count(String destination) {
        final JmsTemplate template = XDAT.getContextService().getBean(JmsTemplate.class);
        if (template == null) {
            log.warn("Unable to find JMS template while trying to count message in queue name {}", destination);
            return 0;
        }
        final int count = ObjectUtils.defaultIfNull(template.browse(destination, MESSAGE_COUNTER_CALLBACK), 0);
        log.debug("There are {} messages in queue {}", count, destination);
        return count;
    }

    public static void sendJmsRequest(final JmsTemplate template, final String queueName, final Object request) {
        final Destination destination = XDAT.getContextService().getBeanSafely(queueName, Destination.class);
        if (destination == null) {
            log.error("Unable to find destination for queue name {}", queueName);
            return;
        }
        template.convertAndSend(destination, request, (processor) -> {
            processor.setStringProperty("taskId", queueName);
            return processor;
        });
    }
}


