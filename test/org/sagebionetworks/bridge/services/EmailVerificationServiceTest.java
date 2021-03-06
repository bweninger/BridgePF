package org.sagebionetworks.bridge.services;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClient;
import com.amazonaws.services.simpleemail.model.GetIdentityVerificationAttributesRequest;
import com.amazonaws.services.simpleemail.model.GetIdentityVerificationAttributesResult;
import com.amazonaws.services.simpleemail.model.IdentityVerificationAttributes;
import com.amazonaws.services.simpleemail.model.VerifyEmailIdentityRequest;
import com.google.common.collect.Maps;

@RunWith(MockitoJUnitRunner.class)
public class EmailVerificationServiceTest {

    private static final String EMAIL_ADDRESS = "foo@foo.com";
    
    @Mock
    private AmazonSimpleEmailServiceClient sesClient;
    @Mock
    private GetIdentityVerificationAttributesResult result;
    @Mock
    private IdentityVerificationAttributes attributes;

    private EmailVerificationService service;
    
    private ArgumentCaptor<GetIdentityVerificationAttributesRequest> getCaptor;
    
    @Before
    public void before() {
        service = new EmailVerificationService();
        service.setAmazonSimpleEmailServiceClient(sesClient);
    }

    private void mockSession(String status) {
        getCaptor = ArgumentCaptor.forClass(GetIdentityVerificationAttributesRequest.class);
                
        Map<String,IdentityVerificationAttributes> map = Maps.newHashMap();
        map.put(EMAIL_ADDRESS, attributes);
        when(result.getVerificationAttributes()).thenReturn(map);
        when(attributes.getVerificationStatus()).thenReturn(status); // aka unverified
        when(sesClient.getIdentityVerificationAttributes(any())).thenReturn(result);
    }
    
    @Test
    public void verifiedEmailTakesNoAction() {
        mockSession("Success");

        EmailVerificationStatus status = service.verifyEmailAddress(EMAIL_ADDRESS);
        
        assertEquals(EmailVerificationStatus.VERIFIED, status);
        verify(sesClient, never()).verifyEmailIdentity(any());
        verify(sesClient).getIdentityVerificationAttributes(getCaptor.capture());
        assertEquals(EMAIL_ADDRESS, getCaptor.getValue().getIdentities().get(0));
    }
    
    @Test
    public void ifUnverifiedAttemptsToResendVerification() {
        mockSession("Failure");

        EmailVerificationStatus status = service.verifyEmailAddress(EMAIL_ADDRESS);
        
        assertEquals(EmailVerificationStatus.PENDING, status);
        verify(sesClient).verifyEmailIdentity(any());
        verify(sesClient).getIdentityVerificationAttributes(getCaptor.capture());
        assertEquals(EMAIL_ADDRESS, getCaptor.getValue().getIdentities().get(0));
    }
    
    @Test
    public void emailDoesntExistRequestVerification() {
        ArgumentCaptor<VerifyEmailIdentityRequest> verifyCaptor = ArgumentCaptor.forClass(VerifyEmailIdentityRequest.class);
        mockSession(null);
        
        EmailVerificationStatus status = service.verifyEmailAddress(EMAIL_ADDRESS);
        
        assertEquals(EmailVerificationStatus.PENDING, status);
        verify(sesClient).verifyEmailIdentity(verifyCaptor.capture());
        verify(sesClient).getIdentityVerificationAttributes(getCaptor.capture());
        assertEquals(EMAIL_ADDRESS, verifyCaptor.getValue().getEmailAddress());
        assertEquals(EMAIL_ADDRESS, getCaptor.getValue().getIdentities().get(0));
    }

    @Test
    public void canResendRegardlessOfStatus() {
        ArgumentCaptor<VerifyEmailIdentityRequest> verifyCaptor = ArgumentCaptor.forClass(VerifyEmailIdentityRequest.class);
        mockSession("Success");
        
        EmailVerificationStatus status = service.sendVerifyEmailRequest(EMAIL_ADDRESS);
        
        assertEquals(EmailVerificationStatus.PENDING, status);
        verify(sesClient).verifyEmailIdentity(verifyCaptor.capture());
        assertEquals(EMAIL_ADDRESS, verifyCaptor.getValue().getEmailAddress());
    }
    
    @Test
    public void getEmailStatus() {
        mockSession("Success");
        
        EmailVerificationStatus status = service.getEmailStatus(EMAIL_ADDRESS);
        
        verify(sesClient).getIdentityVerificationAttributes(any());
        assertEquals(EmailVerificationStatus.VERIFIED, status);
    }
    
    @Test
    public void getEmailStatusAttributesNull() {
        getCaptor = ArgumentCaptor.forClass(GetIdentityVerificationAttributesRequest.class);
        
        Map<String,IdentityVerificationAttributes> map = Maps.newHashMap();
        map.put(EMAIL_ADDRESS, attributes);
        when(result.getVerificationAttributes()).thenReturn(null);
        when(sesClient.getIdentityVerificationAttributes(any())).thenReturn(result);
        
        EmailVerificationStatus status = service.getEmailStatus(EMAIL_ADDRESS);
        
        verify(sesClient).getIdentityVerificationAttributes(any());
        assertEquals(EmailVerificationStatus.UNVERIFIED, status);
    }
    
    @Test
    public void getEmailStatusVerificationStatusNull() {
        getCaptor = ArgumentCaptor.forClass(GetIdentityVerificationAttributesRequest.class);
        
        Map<String,IdentityVerificationAttributes> map = Maps.newHashMap();
        map.put(EMAIL_ADDRESS, attributes);
        when(result.getVerificationAttributes()).thenReturn(map);
        when(attributes.getVerificationStatus()).thenReturn(null);
        when(sesClient.getIdentityVerificationAttributes(any())).thenReturn(result);
        
        EmailVerificationStatus status = service.getEmailStatus(EMAIL_ADDRESS);
        
        verify(sesClient).getIdentityVerificationAttributes(any());
        assertEquals(EmailVerificationStatus.UNVERIFIED, status);
    }
    
    @Test
    public void sendVerifyEmailRequest() {
        mockSession("Success");
        service.sendVerifyEmailRequest(EMAIL_ADDRESS);
        
        verify(sesClient).verifyEmailIdentity(any());
    }

}
