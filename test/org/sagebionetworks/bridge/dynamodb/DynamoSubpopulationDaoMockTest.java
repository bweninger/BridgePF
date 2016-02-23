package org.sagebionetworks.bridge.dynamodb;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.bridge.TestConstants.TEST_STUDY;
import static org.sagebionetworks.bridge.TestConstants.TEST_STUDY_IDENTIFIER;

import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.runners.MockitoJUnitRunner;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.dao.CriteriaDao;
import org.sagebionetworks.bridge.dao.StudyConsentDao;
import org.sagebionetworks.bridge.models.ClientInfo;
import org.sagebionetworks.bridge.models.Criteria;
import org.sagebionetworks.bridge.models.CriteriaContext;
import org.sagebionetworks.bridge.models.subpopulations.Subpopulation;
import org.sagebionetworks.bridge.models.subpopulations.SubpopulationGuid;

import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.PaginatedQueryList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

@RunWith(MockitoJUnitRunner.class)
public class DynamoSubpopulationDaoMockTest {
    
    private static final Set<String> ALL_OF_GROUPS = Sets.newHashSet("a","b");
    private static final Set<String> NONE_OF_GROUPS = Sets.newHashSet("c","d");
    private static final SubpopulationGuid SUBPOP_GUID = SubpopulationGuid.create("AAA");

    @Spy
    private DynamoSubpopulationDao dao;
    
    @Mock
    private DynamoDBMapper mapper;
    
    @Mock
    private StudyConsentDao studyConsentDao;
    
    @Mock
    private CriteriaDao criteriaDao;

    @SuppressWarnings("unchecked")
    @Before
    public void before() {
        dao.setMapper(mapper);
        dao.setStudyConsentDao(studyConsentDao);
        dao.setCriteriaDao(criteriaDao);
        
        List<DynamoSubpopulation> list = Lists.newArrayList((DynamoSubpopulation)createSubpopulation());

        PaginatedQueryList<DynamoSubpopulation> page = mock(PaginatedQueryList.class);
        when(page.stream()).thenReturn(list.stream());

        doReturn(createSubpopulation()).when(mapper).load(any());
        doReturn(page).when(mapper).query(eq(DynamoSubpopulation.class), any());
        
        Criteria criteria = Criteria.create();
        criteria.setMinAppVersion(2);
        criteria.setMaxAppVersion(10);
        criteria.setAllOfGroups(ALL_OF_GROUPS);
        criteria.setNoneOfGroups(NONE_OF_GROUPS);
        when(criteriaDao.getCriteria(any())).thenReturn(criteria);
    }
    
    @Test
    public void createSubpopulationWritesCriteria() {
        Subpopulation subpop = createSubpopulation();
        
        dao.createSubpopulation(subpop);
        
        Criteria criteria = subpop.getCriteria();
        assertCriteria(criteria);
        
        verify(criteriaDao).createOrUpdateCriteria(criteria);
    }
    
    @Test
    public void createDefaultSubpopulationWritesCriteria() {
        Subpopulation subpop = dao.createDefaultSubpopulation(TEST_STUDY);
        
        Criteria criteria = subpop.getCriteria();
        assertEquals(new Integer(0), criteria.getMinAppVersion());
        
        verify(criteriaDao).createOrUpdateCriteria(criteria);
    }
    
    @Test
    public void getSubpopulationRetrievesCriteria() {
        Subpopulation subpop = dao.getSubpopulation(TEST_STUDY, SUBPOP_GUID);
        
        Criteria criteria = subpop.getCriteria();
        assertCriteria(criteria);
        
        verify(criteriaDao).getCriteria(subpop.getKey());
        verifyNoMoreInteractions(criteriaDao);
    }
    
    @Test
    public void getSubpopulationConstructsCriteriaIfNotSaved() {
        when(criteriaDao.getCriteria(any())).thenReturn(null);
        
        Subpopulation subpop = dao.getSubpopulation(TEST_STUDY, SUBPOP_GUID);
        Criteria criteria = subpop.getCriteria();
        assertCriteria(criteria);
        
        verify(criteriaDao).getCriteria(subpop.getKey());
    }
    
    @Test
    public void getSubpopulationsForUserRetrievesCriteria() {
        CriteriaContext context = createContext();
        
        List<Subpopulation> subpops = dao.getSubpopulationsForUser(context);
        Subpopulation subpop = subpops.get(0);
        Criteria criteria = subpop.getCriteria();
        assertCriteria(criteria);
        
        verify(criteriaDao).getCriteria(subpop.getKey());
        verifyNoMoreInteractions(criteriaDao);
    }

    @Test
    public void getSubpopulationsForUserConstructsCriteriaIfNotSaved() {
        when(criteriaDao.getCriteria(any())).thenReturn(null);
        CriteriaContext context = createContext();
        
        List<Subpopulation> subpops = dao.getSubpopulationsForUser(context);
        Subpopulation subpop = subpops.get(0);
        Criteria criteria = subpop.getCriteria();
        assertCriteria(criteria);
        
        verify(criteriaDao).getCriteria(subpop.getKey());
    }
        
    @Test
    public void physicalDeleteSubpopulationDeletesCriteria() {
        dao.deleteSubpopulation(TEST_STUDY, SUBPOP_GUID, true);
        
        verify(criteriaDao).deleteCriteria(createSubpopulation().getKey());
    }
    
    @Test
    public void logicalDeleteSubpopulationDoesNotDeleteCriteria() {
        dao.deleteSubpopulation(TEST_STUDY, SUBPOP_GUID, false);
        
        verify(criteriaDao, never()).deleteCriteria(createSubpopulation().getKey());
    }
    
    @Test
    public void updateSubpopulationUpdatesCriteriaFromSubpop() {
        // This subpopulation has the criteria fields, but no object
        Subpopulation subpop = createSubpopulation();
        subpop.setVersion(1L);
        
        ArgumentCaptor<Criteria> criteriaCaptor = ArgumentCaptor.forClass(Criteria.class);
        
        Subpopulation updatedSubpop = dao.updateSubpopulation(subpop);
        assertCriteria(updatedSubpop.getCriteria()); // has been copied into this object
        
        verify(criteriaDao).createOrUpdateCriteria(criteriaCaptor.capture());
        Criteria savedCriteria = criteriaCaptor.getValue();
        assertCriteria(savedCriteria); // has been saved separately.
    }
    
    @Test
    public void updateSubpopulationUpdatesCriteriaFromObject() {
        Criteria criteria = Criteria.create();
        criteria.setMinAppVersion(2);
        criteria.setMaxAppVersion(10);
        criteria.setAllOfGroups(ALL_OF_GROUPS);
        criteria.setNoneOfGroups(NONE_OF_GROUPS);

        Subpopulation subpopWithCritObject = Subpopulation.create();
        subpopWithCritObject.setStudyIdentifier(TEST_STUDY_IDENTIFIER);
        subpopWithCritObject.setGuidString(BridgeUtils.generateGuid());
        subpopWithCritObject.setVersion(1L);
        subpopWithCritObject.setCriteria(criteria);
        
        reset(mapper);
        doReturn(subpopWithCritObject).when(mapper).load(any());
        
        ArgumentCaptor<Criteria> criteriaCaptor = ArgumentCaptor.forClass(Criteria.class);
        
        Subpopulation updatedSubpop = dao.updateSubpopulation(subpopWithCritObject);
        assertCriteria(updatedSubpop.getCriteria()); // has been copied into this object
        
        verify(criteriaDao).getCriteria(subpopWithCritObject.getKey());
        verify(criteriaDao).createOrUpdateCriteria(criteriaCaptor.capture());
        Criteria savedCriteria = criteriaCaptor.getValue();
        assertCriteria(savedCriteria); // has been saved separately.
    }
    
    @Test
    public void getSubpopulationsCreatesCriteria() {
        // The test subpopulation in the list that is returned from the mock mapper does not have
        // a criteria object. So it will be created as part of loading.
        List<Subpopulation> list = dao.getSubpopulations(TEST_STUDY, false, true);
        assertCriteria(list.get(0));
        
        // Making a point of the fact that there is no criteria object
        doReturn(null).when(criteriaDao).getCriteria(any());
        
        verify(criteriaDao).getCriteria(list.get(0).getKey());
    }
    
    @Test
    public void getSubpopulationsRetrievesCriteria() {
        // The test subpopulation in the list that is returned from the mock mapper does not have
        // a criteria object. So it will be created as part of loading.
        List<Subpopulation> list = dao.getSubpopulations(TEST_STUDY, false, true);
        assertCriteria(list.get(0));
        
        // In this case it actually returns a criteria object.
        verify(criteriaDao).getCriteria(list.get(0).getKey());
    }
    
    @Test
    public void deleteAllSubpopulationsDeletesCriteria() {
        // There's one subpopulation
        dao.deleteAllSubpopulations(TEST_STUDY);
        
        verify(criteriaDao).deleteCriteria(createSubpopulation().getKey());
    }
    
    @Test
    public void criteriaTableTakesPrecedenceOnGet() {
        Criteria criteria = Criteria.create();
        criteria.setMinAppVersion(12);
        criteria.setMaxAppVersion(20);
        criteria.setAllOfGroups(NONE_OF_GROUPS);
        criteria.setNoneOfGroups(ALL_OF_GROUPS);
        
        reset(criteriaDao);
        doReturn(criteria).when(criteriaDao).getCriteria(any());
        
        Subpopulation subpop = dao.getSubpopulation(TEST_STUDY, SUBPOP_GUID);
        Criteria retrievedCriteria = subpop.getCriteria();
        assertEquals(new Integer(12), retrievedCriteria.getMinAppVersion());
        assertEquals(new Integer(20), retrievedCriteria.getMaxAppVersion());
        assertEquals(NONE_OF_GROUPS, retrievedCriteria.getAllOfGroups());
        assertEquals(ALL_OF_GROUPS, retrievedCriteria.getNoneOfGroups());
    }
    
    @Test
    public void criteriaTableTakesPrecedenceOnGetList() {
        Criteria criteria = Criteria.create();
        criteria.setMinAppVersion(12);
        criteria.setMaxAppVersion(20);
        criteria.setAllOfGroups(NONE_OF_GROUPS);
        criteria.setNoneOfGroups(ALL_OF_GROUPS);
        
        reset(criteriaDao);
        doReturn(criteria).when(criteriaDao).getCriteria(any());
        
        List<Subpopulation> subpops = dao.getSubpopulations(TEST_STUDY, false, true);
        Criteria retrievedCriteria = subpops.get(0).getCriteria();
        assertEquals(new Integer(12), retrievedCriteria.getMinAppVersion());
        assertEquals(new Integer(20), retrievedCriteria.getMaxAppVersion());
        assertEquals(NONE_OF_GROUPS, retrievedCriteria.getAllOfGroups());
        assertEquals(ALL_OF_GROUPS, retrievedCriteria.getNoneOfGroups());
    }
    
    private CriteriaContext createContext() {
        return new CriteriaContext.Builder()
                .withStudyIdentifier(TEST_STUDY)
                .withUserDataGroups(ALL_OF_GROUPS)
                .withClientInfo(ClientInfo.UNKNOWN_CLIENT)
                .build();
    }
    
    private Subpopulation createSubpopulation() {
        Subpopulation subpop = Subpopulation.create();
        subpop.setGuid(SUBPOP_GUID);
        subpop.setStudyIdentifier(TEST_STUDY_IDENTIFIER);
        subpop.setMinAppVersion(2);
        subpop.setMaxAppVersion(10);
        subpop.setAllOfGroups(ALL_OF_GROUPS);
        subpop.setNoneOfGroups(NONE_OF_GROUPS);
        return subpop;
    }
    
    private void assertCriteria(Criteria criteria) {
        assertEquals(new Integer(2), criteria.getMinAppVersion());
        assertEquals(new Integer(10), criteria.getMaxAppVersion());
        assertEquals(ALL_OF_GROUPS, criteria.getAllOfGroups());
        assertEquals(NONE_OF_GROUPS, criteria.getNoneOfGroups());
    }
}