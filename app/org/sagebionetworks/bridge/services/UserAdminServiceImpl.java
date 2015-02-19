package org.sagebionetworks.bridge.services;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Iterator;

import org.apache.commons.lang3.StringUtils;
import org.sagebionetworks.bridge.dao.AccountDao;
import org.sagebionetworks.bridge.dao.DistributedLockDao;
import org.sagebionetworks.bridge.exceptions.BridgeServiceException;
import org.sagebionetworks.bridge.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.models.SignIn;
import org.sagebionetworks.bridge.models.SignUp;
import org.sagebionetworks.bridge.models.User;
import org.sagebionetworks.bridge.models.UserSession;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.studies.ConsentSignature;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.redis.RedisKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

public class UserAdminServiceImpl implements UserAdminService {

    private static final Logger logger = LoggerFactory.getLogger(UserAdminServiceImpl.class);

    private AuthenticationServiceImpl authenticationService;
    private AccountDao accountDao;
    private ConsentService consentService;
    private StudyService studyService;
    private DistributedLockDao lockDao;

    public void setAuthenticationService(AuthenticationServiceImpl authenticationService) {
        this.authenticationService = authenticationService;
    }
    
    public void setAccountDao(AccountDao accountDao) {
        this.accountDao = accountDao;
    }

    public void setConsentService(ConsentService consentService) {
        this.consentService = consentService;
    }

    public void setStudyService(StudyService studyService) {
        this.studyService = studyService;
    }

    public void setDistributedLockDao(DistributedLockDao lockDao) {
        this.lockDao = lockDao;
    }

    @Override
    public UserSession createUser(SignUp signUp, Study study, boolean signUserIn, boolean consentUser) {
        checkNotNull(study, "Study cannot be null");
        checkNotNull(signUp, "Sign up cannot be null");
        checkNotNull(signUp.getEmail(), "Sign up email cannot be null");

        authenticationService.signUp(signUp, study, false);

        SignIn signIn = new SignIn(signUp.getUsername(), signUp.getPassword());
        UserSession newUserSession = null;
        try {
            newUserSession = authenticationService.signIn(study, signIn);
        } catch (ConsentRequiredException e) {
            newUserSession = e.getUserSession();
            if (consentUser) {
                String sig = String.format("[Signature for %s]", signUp.getEmail());;
                ConsentSignature consent = ConsentSignature.create(sig, "1989-08-19", null, null);
                consentService.consentToResearch(newUserSession.getUser(), consent, study, false);
            }
        }
        if (!signUserIn) {
            authenticationService.signOut(newUserSession.getSessionToken());
            newUserSession = null;
        }
        return newUserSession;
    }

    @Override
    public void deleteUser(Study study, String email) {
        checkNotNull(study);
        Preconditions.checkArgument(StringUtils.isNotBlank(email));         
        
        Account account = accountDao.getAccount(study, email);
        if (account != null) {
            deleteUser(account);    
        }
    }
    
    void deleteUser(Account account) {
        checkNotNull(account);

        int retryCount = 0;
        boolean shouldRetry = true;
        while (shouldRetry) {
            boolean deleted = deleteUserAttempt(account);
            if (deleted) {
                return;
            }
            shouldRetry = retryCount < 5;
            retryCount++;
            try {
                Thread.sleep(100 * 2 ^ retryCount);
            } catch(InterruptedException ie) {
                throw new BridgeServiceException(ie);
            }
        }
    }

    @Override
    public void deleteAllUsers(String role) {
        Iterator<Account> iterator = accountDao.getAllAccounts();
        while(iterator.hasNext()) {
            Account account = iterator.next();
            if (account.getRoles().contains(role)) {
                deleteUser(account);
            }
        }
    }

    private boolean deleteUserAttempt(Account account) {
        String key = RedisKey.USER_LOCK.getRedisKey(account.getEmail());
        String lock = null;
        boolean success = false;
        try {
            lock = lockDao.acquireLock(User.class, key);
            if (account != null) {
                Study study = studyService.getStudy(account.getStudyIdentifier());
                deleteUserInStudy(study, account);
                accountDao.deleteAccount(study, account.getEmail());
                // Check if the delete succeeded
                success = accountDao.getAccount(study, account.getEmail()) == null ? true : false;
            }
        } catch(Throwable t) {
            success = false;
            logger.error(t.getMessage(), t);
        } finally {
            // This used to silently fail, not there is a precondition check that
            // throws an exception. If there has been an exception, lock == null
            if (lock != null) {
                lockDao.releaseLock(User.class, key, lock);
            }
        }
        return success;
    }

    private boolean deleteUserInStudy(Study study, Account account) throws BridgeServiceException {
        checkNotNull(study);
        checkNotNull(account);

        try {
            User user = authenticationService.getUser(study, account.getEmail());
            consentService.withdrawConsent(study, user);
            //String healthCode = user.getHealthCode();
            //optionsService.deleteAllParticipantOptions(healthCode);
            return true;
        } catch (Throwable e) {
            logger.error(e.getMessage(), e);
            return false;
        }
    }
}
