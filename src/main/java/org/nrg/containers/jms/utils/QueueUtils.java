package org.nrg.containers.jms.utils;

import java.util.Enumeration;
import java.util.function.Supplier;

import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.QueueBrowser;
import javax.jms.Session;

import org.apache.commons.lang3.StringUtils;
import org.nrg.xdat.XDAT;
import org.springframework.jms.core.BrowserCallback;
import org.springframework.jms.core.JmsTemplate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ClassUtils;

@Slf4j
public class QueueUtils {
	/*
	 * Get the count of the current messages in this queue.
	 */
     public static int count(String destination){
        int count = XDAT.getContextService().getBean(JmsTemplate.class).browse(destination, new BrowserCallback<Integer>() {
            public Integer doInJms(final Session session, final QueueBrowser browser) throws JMSException {
                Enumeration enumeration = browser.getEnumeration();
                int counter = 0;
                while (enumeration.hasMoreElements()) {
                    Message msg = (Message) enumeration.nextElement();
                    //System.out.println(String.format("\tFound : %s", msg));
                    counter += 1;
                }
                return counter;
            }
        });

        log.debug("There are {} messages in queue {}", count, destination);
        return count;
    }

    public static void sendJmsRequest(final String destination, final String message) {
         sendJmsRequest(XDAT.getContextService().getBean(JmsTemplate.class), destination, message);
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


