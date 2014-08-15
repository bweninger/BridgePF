package org.sagebionetworks.bridge.dynamodb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import javax.annotation.Resource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.bridge.dao.ConsentAlreadyExistsException;
import org.sagebionetworks.bridge.models.ResearchConsent;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@ContextConfiguration("file:conf/application-context.xml")
@RunWith(SpringJUnit4ClassRunner.class)
public class DynamoUserConsentDaoTest {

    @Resource
    private DynamoUserConsentDao userConsentDao;

    @Before
    public void before() {
        DynamoTestUtil.clearTable(DynamoUserConsent.class, "name", "birthdate", "give", "studyKey", "consentTimestamp",
                "version");
        DynamoTestUtil.clearTable(DynamoUserConsent1.class, "name", "birthdate", "timestamp", "studyKey", "consentTimestamp",
                "version");
    }

    @After
    public void after() {
        DynamoTestUtil.clearTable(DynamoUserConsent.class, "name", "birthdate", "give", "studyKey", "consentTimestamp",
                "version");
        DynamoTestUtil.clearTable(DynamoUserConsent1.class, "name", "birthdate", "timestamp", "studyKey", "consentTimestamp",
                "version");
    }

    @Test
    public void test() {
        // Not consented yet
        final String healthCode = "hc789";
        final DynamoStudyConsent consent = new DynamoStudyConsent();
        consent.setStudyKey("study123");
        consent.setTimestamp(123L);
        assertFalse(userConsentDao.hasConsented(healthCode, consent));
        
        // Give consent
        final ResearchConsent researchConsent = new ResearchConsent("John Smith", "1999-12-01");
        userConsentDao.giveConsent(healthCode, consent, researchConsent);
        assertTrue(userConsentDao.hasConsented(healthCode, consent));
        
        // Cannot give consent again if already consented
        try {
            userConsentDao.giveConsent(healthCode, consent, researchConsent);
        } catch (ConsentAlreadyExistsException e) {
            assertTrue(true); // Expected
        }
        // Withdraw
        userConsentDao.withdrawConsent(healthCode, consent);
        assertFalse(userConsentDao.hasConsented(healthCode, consent));
        
        // Can give consent again if the previous consent is withdrawn
        userConsentDao.giveConsent(healthCode, consent, researchConsent);
        assertTrue(userConsentDao.hasConsented(healthCode, consent));
        
        // Withdraw again
        userConsentDao.withdrawConsent(healthCode, consent);
        assertFalse(userConsentDao.hasConsented(healthCode, consent));
    }
}
