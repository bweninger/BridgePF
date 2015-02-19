package org.sagebionetworks.bridge.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import javax.annotation.Resource;

import org.apache.commons.codec.binary.Base64;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.bridge.TestUserAdminHelper;
import org.sagebionetworks.bridge.TestUserAdminHelper.TestUser;
import org.sagebionetworks.bridge.dao.AccountDao;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.studies.ConsentSignature;
import org.sagebionetworks.bridge.models.studies.Study;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@ContextConfiguration("classpath:test-context.xml")
@RunWith(SpringJUnit4ClassRunner.class)
public class AccountEncryptionServiceImplTest {

    @Resource
    private AccountEncryptionService accountEncryptionService;

    @Resource
    private TestUserAdminHelper helper;

    @Resource
    private AccountDao accountDao;

    private TestUser testUser;

    @Before
    public void before() {
        testUser = helper.createUser(AccountEncryptionServiceImplTest.class, true, false, null);
    }

    @After
    public void after() {
        helper.deleteUser(testUser);
    }

    @Test
    public void testConsentSignature() {
        Study study = testUser.getStudy();
        Account account = accountDao.getAccount(testUser.getStudy(), testUser.getEmail());
        ConsentSignature consentSignature = ConsentSignature.create("Test User", "1977-07-29",
                Base64.encodeBase64String("some image".getBytes()), "image/png");
        accountEncryptionService.putConsentSignature(study, account, consentSignature);
        ConsentSignature signature = accountEncryptionService.getConsentSignature(account);
        assertNotNull(signature);
        assertEquals(consentSignature.getName(), signature.getName());
        assertEquals(consentSignature.getBirthdate(), signature.getBirthdate());
        assertEquals(consentSignature.getImageData(), signature.getImageData());
        assertEquals(consentSignature.getImageMimeType(), signature.getImageMimeType());
        accountEncryptionService.removeConsentSignature(study, account);
        try {
            accountEncryptionService.getConsentSignature(account);
            fail("EntityNotFoundException expected.");
        } catch (EntityNotFoundException e) {
            assertTrue("EntityNotFoundException expected.", true);
        }
    }

    @Test(expected = EntityNotFoundException.class)
    public void testConsentSignatureEntityNotFoundException() {
        Account account = accountDao.getAccount(testUser.getStudy(), testUser.getEmail());
        accountEncryptionService.getConsentSignature(account);
    }
}
