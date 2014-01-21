/**
 * Copyright 2014 Callista Enterprise AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package se.callistaenterprise.labs.activemq;
import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertTrue;
import static junit.framework.TestCase.fail;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.QueueReceiver;
import javax.jms.QueueSession;
import javax.jms.Session;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerFactory;
import org.apache.activemq.broker.BrokerService;
import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SimpleAuthenticationPluginTest {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleAuthenticationPluginTest.class);
	private static final String CONFIG_URL = "xbean:target/classes/conf/activemq.xml";
	private static final String BROKER_URL = "failover:(tcp://localhost:61616)";
	private static final String SEC_PROPERTY_FILE = "target/classes/conf/credentials.properties";
	private static final String OPEN_QUEUE = "OPEN.Q1";
	private static final String AUTH_OK_QUEUE = "APP1.Q1";
	private static final String AUTH_FAIL_QUEUE = "APP2.Q1";
	
    private Properties secProps = null;
	private String usr = null;
	private String pwd = null;
	private BrokerService broker = null;
	private QueueConnection anonymousConnection = null;
	private QueueSession anonymousSession = null;
	private QueueConnection authenticatedConnection = null;
	private QueueSession authenticatedSession = null;
	
    @Test
    public void test1AnonumousAccessOk() throws Exception {
    	LOG.info("Verify that an Anonumous user can read and write to an open queue");
		boolean found = sendAndReceive(anonymousSession, OPEN_QUEUE);
		assertTrue("Failed to receive the message sent", found);
    }

    @Test
    public void test2AnonumousAccessFail() throws Exception {
    	LOG.info("Verify that an Anonumous user can't read nor write to a queue that requires authorization");
    	try {
    		sendAndReceive(anonymousSession, AUTH_OK_QUEUE);
    		fail("Expected an error to occurr");
    	} catch (JMSException ex) {
    		assertEquals("javax.jms.JMSException: User anonymous is not authorized to write to: queue://" + AUTH_OK_QUEUE, ex.toString());
    	}

    	try {
    		sendAndReceive(anonymousSession, AUTH_FAIL_QUEUE);
    		fail("Expected an error to occurr");
    	} catch (JMSException ex) {
    		assertEquals("javax.jms.JMSException: User anonymous is not authorized to write to: queue://" + AUTH_FAIL_QUEUE, ex.toString());
    	}
    }

    @Test
    public void test3AuthenticatedAccessOk() throws Exception {
    	LOG.info("Verify that an Authenticated user can read and write to both an open queue and authorized queues");

		boolean found = sendAndReceive(authenticatedSession, OPEN_QUEUE);
		assertTrue("Failed to receive the message sent", found);

		found = sendAndReceive(authenticatedSession, AUTH_OK_QUEUE);
		assertTrue("Failed to receive the message sent", found);
    }

    @Test
    public void test4AuthenticatedAccessFail() throws Exception {
    	LOG.info("Verify that an Authenticated user can't read nor write to a queue that it is not authorized for");

    	try {
    		sendAndReceive(authenticatedSession, AUTH_FAIL_QUEUE);
    		fail("Expected an error to occurr");
    	} catch (JMSException ex) {
    		assertEquals("javax.jms.JMSException: User " + usr + " is not authorized to write to: queue://" + AUTH_FAIL_QUEUE, ex.toString());
    	}
    }

    @Before
    public void setUp() throws Exception {
        System.setProperty("activemq.base", "target");
        System.setProperty("activemq.home", "target"); // not a valid home but ok for xml validation
        System.setProperty("activemq.data", "target");
        System.setProperty("activemq.conf", "target/classes/conf");
        secProps = new Properties();
        secProps.load(new FileInputStream(new File(SEC_PROPERTY_FILE)));

        usr = secProps.getProperty("app1.usr");
        pwd = secProps.getProperty("app1.pwd");
        
        LOG.debug("Start the broker using config: " + CONFIG_URL);
        broker = BrokerFactory.createBroker(CONFIG_URL);
        broker.start();

        QueueConnectionFactory qf = new ActiveMQConnectionFactory(BROKER_URL);

        LOG.debug("Connect to the broker using a anonymous user");
    	anonymousConnection = qf.createQueueConnection();
        anonymousConnection.start();
        anonymousSession = anonymousConnection.createQueueSession(false, Session.AUTO_ACKNOWLEDGE);

        LOG.debug("Connect to the broker using a authenticated user");
        authenticatedConnection = qf.createQueueConnection(usr, pwd);
        authenticatedConnection.start();
        authenticatedSession = authenticatedConnection.createQueueSession(false, Session.AUTO_ACKNOWLEDGE);
    }

    @After
    public void tearDown() throws Exception {
        LOG.debug("Stop the broker...");
        
        if (anonymousSession != null) {
        	try {
        		anonymousSession.close();
        	} catch (Exception ex) {}
        }
        
        if (anonymousConnection != null) {
        	try {
	        	anonymousConnection.stop();
	        	anonymousConnection.close();
        	} catch (Exception ex) {}
        }
        
        if (authenticatedSession != null) {
        	try {
        		authenticatedSession.close();
        	} catch (Exception ex) {}
        }
        
        if (authenticatedConnection != null) {
        	try {
        		authenticatedConnection.stop();
        		authenticatedConnection.close();
        	} catch (Exception ex) {}
        }
        
        if (broker != null) {
        	try {
	            broker.stop();
	            broker.waitUntilStopped();
        	} catch (Exception ex) {}
        }        
    }

	private boolean sendAndReceive(QueueSession session, String queueName) throws JMSException {
		Queue queue = session.createQueue(queueName);
		MessageProducer producer = session.createProducer(queue);
		Message message = session.createMessage();  
        producer.send(message);
		String msgId = message.getJMSMessageID();
		
        QueueReceiver receiver = session.createReceiver(queue);
        boolean found = false;
        Message msg = null;
        do {
	        msg = receiver.receive(100);   
	        if (msg != null && msg.getJMSMessageID().equals(msgId)) found = true;
		} while (msg != null);
		return found;
	}
}