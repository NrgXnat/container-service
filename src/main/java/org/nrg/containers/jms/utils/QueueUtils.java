package org.nrg.containers.jms.utils;

import java.util.Enumeration;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.QueueBrowser;
import javax.jms.Session;

import org.nrg.xdat.XDAT;
import org.springframework.jms.core.BrowserCallback;
import org.springframework.jms.core.JmsTemplate;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class QueueUtils {
	/*
	 * Get the count of the current messages in this queue.
	 */
     static public int count(String destination){

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
}
	

