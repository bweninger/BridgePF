package org.sagebionetworks.bridge.events;

import org.sagebionetworks.bridge.models.Study;
import org.sagebionetworks.bridge.models.User;
import org.springframework.context.ApplicationEvent;

public class UserEnrolledEvent extends ApplicationEvent {
    private static final long serialVersionUID = 4061850059583989537L;

    private Study study; 
    
    public UserEnrolledEvent(Object source, Study study) {
        super(source);
        this.study = study; 
    }
    
    public User getUser() {
        return (User)getSource();
    }
    
    public Study getStudy() {
        return study;
    }

}