package org.sagebionetworks.bridge.models;

import org.sagebionetworks.bridge.json.JsonUtils;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.models.studies.StudyIdentifierImpl;

import com.fasterxml.jackson.databind.JsonNode;

public class Email implements BridgeEntity {

    private static final String EMAIL_FIELD = "email";
    private static final String STUDY_FIELD = "study";
    
    private final String email;
    private final StudyIdentifier studyIdentifier;
    
    public Email(StudyIdentifier studyIdentifier, String email) {
        this.studyIdentifier = studyIdentifier;
        this.email = email;
    }
    
    public static final Email fromJson(JsonNode node) {
        String email = JsonUtils.asText(node, EMAIL_FIELD);
        String study = JsonUtils.asText(node, STUDY_FIELD);
        StudyIdentifier studyIdentifier = (study == null) ? null : new StudyIdentifierImpl(study);
        return new Email(studyIdentifier, email);
    }

    public String getEmail() {
        return email;
    }
    
    public StudyIdentifier getStudyIdentifier() {
        return studyIdentifier;
    }

}
