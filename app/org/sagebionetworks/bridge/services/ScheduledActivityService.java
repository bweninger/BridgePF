package org.sagebionetworks.bridge.services;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.sagebionetworks.bridge.util.BridgeCollectors.toImmutableList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.sagebionetworks.bridge.dao.ScheduledActivityDao;
import org.sagebionetworks.bridge.exceptions.BadRequestException;
import org.sagebionetworks.bridge.models.CriteriaContext;
import org.sagebionetworks.bridge.models.schedules.Activity;
import org.sagebionetworks.bridge.models.schedules.ActivityType;
import org.sagebionetworks.bridge.models.schedules.CompoundActivity;
import org.sagebionetworks.bridge.models.schedules.CompoundActivityDefinition;
import org.sagebionetworks.bridge.models.schedules.Schedule;
import org.sagebionetworks.bridge.models.schedules.ScheduleContext;
import org.sagebionetworks.bridge.models.schedules.SchedulePlan;
import org.sagebionetworks.bridge.models.schedules.ScheduledActivity;
import org.sagebionetworks.bridge.models.schedules.ScheduledActivityStatus;
import org.sagebionetworks.bridge.models.schedules.SchemaReference;
import org.sagebionetworks.bridge.models.schedules.SurveyReference;
import org.sagebionetworks.bridge.models.schedules.TaskReference;
import org.sagebionetworks.bridge.models.surveys.Survey;
import org.sagebionetworks.bridge.models.upload.UploadSchema;
import org.sagebionetworks.bridge.validators.ScheduleContextValidator;
import org.sagebionetworks.bridge.validators.Validate;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

@Component
public class ScheduledActivityService {
    
    private static final String ENROLLMENT = "enrollment";

    private static final ScheduleContextValidator VALIDATOR = new ScheduleContextValidator();
    
    private ScheduledActivityDao activityDao;
    
    private ActivityEventService activityEventService;

    private CompoundActivityDefinitionService compoundActivityDefinitionService;

    private SchedulePlanService schedulePlanService;

    private UploadSchemaService schemaService;

    private SurveyService surveyService;
    
    @Autowired
    public final void setScheduledActivityDao(ScheduledActivityDao activityDao) {
        this.activityDao = activityDao;
    }
    @Autowired
    public final void setActivityEventService(ActivityEventService activityEventService) {
        this.activityEventService = activityEventService;
    }

    /**
     * Compound Activity Definition service, used to resolve compound activities (references) in activity schedules.
     */
    @Autowired
    public final void setCompoundActivityDefinitionService(
            CompoundActivityDefinitionService compoundActivityDefinitionService) {
        this.compoundActivityDefinitionService = compoundActivityDefinitionService;
    }

    @Autowired
    public final void setSchedulePlanService(SchedulePlanService schedulePlanService) {
        this.schedulePlanService = schedulePlanService;
    }

    /** Schema service, used to resolve schema revision numbers for schema references in activities. */
    @Autowired
    public final void setSchemaService(UploadSchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @Autowired
    public final void setSurveyService(SurveyService surveyService) {
        this.surveyService = surveyService;
    }
    
    public List<ScheduledActivity> getScheduledActivities(ScheduleContext context) {
        checkNotNull(context);
        
        Validate.nonEntityThrowingException(VALIDATOR, context);
        
        // Add events for scheduling
        Map<String, DateTime> events = createEventsMap(context);
        ScheduleContext newContext = new ScheduleContext.Builder().withContext(context).withEvents(events).build();
        
        // Get scheduled activities, persisted activities, and compare them
        List<ScheduledActivity> scheduledActivities = scheduleActivitiesForPlans(newContext);
        List<ScheduledActivity> dbActivities = activityDao.getActivities(newContext.getEndsOn().getZone(), scheduledActivities);
        
        List<ScheduledActivity> saves = updateActivitiesAndCollectSaves(scheduledActivities, dbActivities);
        activityDao.saveActivities(saves);
        
        return orderActivities(scheduledActivities);
    }
    
    public void updateScheduledActivities(String healthCode, List<ScheduledActivity> scheduledActivities) {
        checkArgument(isNotBlank(healthCode));
        checkNotNull(scheduledActivities);
        
        List<ScheduledActivity> activitiesToSave = Lists.newArrayListWithCapacity(scheduledActivities.size());
        for (int i=0; i < scheduledActivities.size(); i++) {
            ScheduledActivity schActivity = scheduledActivities.get(i);
            if (schActivity == null) {
                throw new BadRequestException("A task in the array is null");
            }
            if (schActivity.getGuid() == null) {
                throw new BadRequestException(String.format("Task #%s has no GUID", i));
            }
            if (schActivity.getStartedOn() != null || schActivity.getFinishedOn() != null) {
                // We do not need to add the time zone here. Not returning these to the user.
                ScheduledActivity dbActivity = activityDao.getActivity(healthCode, schActivity.getGuid());
                if (schActivity.getStartedOn() != null) {
                    dbActivity.setStartedOn(schActivity.getStartedOn());
                }
                if (schActivity.getFinishedOn() != null) {
                    dbActivity.setFinishedOn(schActivity.getFinishedOn());
                    activityEventService.publishActivityFinishedEvent(dbActivity);
                }
                activitiesToSave.add(dbActivity);
            }
        }
        activityDao.updateActivities(healthCode, activitiesToSave);
    }
    
    public void deleteActivitiesForUser(String healthCode) {
        checkArgument(isNotBlank(healthCode));
        
        activityDao.deleteActivitiesForUser(healthCode);
    }
    
    protected List<ScheduledActivity> updateActivitiesAndCollectSaves(List<ScheduledActivity> scheduledActivities, List<ScheduledActivity> dbActivities) {
        Map<String, ScheduledActivity> dbMap = Maps.uniqueIndex(dbActivities, ScheduledActivity::getGuid);
        
        // Find activities that have been scheduled, but not saved. If they have been scheduled and saved,
        // replace the scheduled activity with the database activity so the existing state is returned to 
        // user (startedOn/finishedOn). Don't save expired tasks though.
        List<ScheduledActivity> saves = Lists.newArrayList();
        for (int i=0; i < scheduledActivities.size(); i++) {
            ScheduledActivity activity = scheduledActivities.get(i);

            ScheduledActivity dbActivity = dbMap.get(activity.getGuid());
            if (dbActivity != null && !ScheduledActivityStatus.UPDATABLE_STATUSES.contains(dbActivity.getStatus())) {
                // Once the activity is in the database and is in a non-updatable state, we should use the one from the
                // database. Otherwise, either (a) it doesn't exist yet and needs to be persisted or (b) the user
                // hasn't interacted with it yet, so we can safely replace it with the newly generated one, which may
                // have updated schemas or surveys.
                //
                // Note that this only works because the scheduled activity guid is actually the schedule plan's
                // activity guid concatenated with scheduled time. So when the scheduler regenerates the scheduled
                // activity, it always has the same guid.
                scheduledActivities.set(i, dbActivity);
            } else if (activity.getStatus() != ScheduledActivityStatus.EXPIRED) {
                saves.add(activity);
            }
        }
        return saves;
    }
    
    protected List<ScheduledActivity> orderActivities(List<ScheduledActivity> activities) {
        return activities.stream()
            .filter(activity -> ScheduledActivityStatus.VISIBLE_STATUSES.contains(activity.getStatus()))
            .sorted(comparing(ScheduledActivity::getScheduledOn))
            .collect(toImmutableList());
    }
    
    private Map<String, DateTime> createEventsMap(ScheduleContext context) {
        Map<String,DateTime> events = activityEventService.getActivityEventMap(context.getCriteriaContext().getHealthCode());

        ImmutableMap.Builder<String,DateTime> builder = new ImmutableMap.Builder<String, DateTime>();
        if (!events.containsKey(ENROLLMENT)) {
            builder.put(ENROLLMENT, context.getAccountCreatedOn().withZone(context.getInitialTimeZone()));
        }
        for(Map.Entry<String, DateTime> entry : events.entrySet()) {
            builder.put(entry.getKey(), entry.getValue().withZone(context.getInitialTimeZone()));
        }
        return builder.build();
    }
    
    protected List<ScheduledActivity> scheduleActivitiesForPlans(ScheduleContext context) {
        // Cache compound activity defs, schemas, and surveys to reduce calls from duplicate requests.
        Map<String, CompoundActivity> compoundActivityCache = new HashMap<>();
        Map<String, SchemaReference> schemaCache = new HashMap<>();
        Map<String, SurveyReference> surveyCache = new HashMap<>();
        List<ScheduledActivity> scheduledActivities = new ArrayList<>();
        
        List<SchedulePlan> plans = schedulePlanService.getSchedulePlans(context.getCriteriaContext().getClientInfo(),
                context.getCriteriaContext().getStudyIdentifier());
        
        for (SchedulePlan plan : plans) {
            Schedule schedule = plan.getStrategy().getScheduleForUser(plan, context);
            if (schedule != null) {
                List<ScheduledActivity> activities = schedule.getScheduler().getScheduledActivities(plan, context);
                List<ScheduledActivity> resolvedActivities = resolveLinks(context.getCriteriaContext(),
                        compoundActivityCache, schemaCache, surveyCache, activities);
                scheduledActivities.addAll(resolvedActivities);    
            }
        }
        return scheduledActivities;
    }
    
    private List<ScheduledActivity> resolveLinks(CriteriaContext context,
            Map<String, CompoundActivity> compoundActivityCache, Map<String, SchemaReference> schemaCache,
            Map<String, SurveyReference> surveyCache, List<ScheduledActivity> activities) {

        return activities.stream().map(schActivity -> {
            Activity activity = schActivity.getActivity();
            ActivityType activityType = activity.getActivityType();

            // Multiplex on activity type and resolve the activity, as needed.
            Activity resolvedActivity = null;
            if (activityType == ActivityType.COMPOUND) {
                // Resolve compound activity.
                CompoundActivity compoundActivity = activity.getCompoundActivity();
                CompoundActivity resolvedCompoundActivity = resolveCompoundActivity(context, compoundActivityCache,
                        schemaCache, surveyCache, compoundActivity);

                // If resolution changed the compound activity, generate a new activity instance that contains it.
                if (!resolvedCompoundActivity.equals(compoundActivity)) {
                    resolvedActivity = new Activity.Builder().withActivity(activity)
                            .withCompoundActivity(resolvedCompoundActivity).build();
                }
            } else if (activityType == ActivityType.SURVEY) {
                SurveyReference surveyRef = activity.getSurvey();
                SurveyReference resolvedSurveyRef = resolveSurvey(context, surveyCache, surveyRef);

                if (!resolvedSurveyRef.equals(surveyRef)) {
                    resolvedActivity = new Activity.Builder().withActivity(activity).withSurvey(resolvedSurveyRef)
                            .build();
                }
            } else if (activityType == ActivityType.TASK) {
                TaskReference taskRef = activity.getTask();
                SchemaReference schemaRef = taskRef.getSchema();

                if (schemaRef != null) {
                    SchemaReference resolvedSchemaRef = resolveSchema(context, schemaCache, schemaRef);

                    if (!resolvedSchemaRef.equals(schemaRef)) {
                        TaskReference resolvedTaskRef = new TaskReference(taskRef.getIdentifier(), resolvedSchemaRef);
                        resolvedActivity = new Activity.Builder().withTask(resolvedTaskRef).build();
                    }
                }
            }

            // Set the activity back into the ScheduledActivity, if needed.
            if (resolvedActivity != null) {
                schActivity.setActivity(resolvedActivity);
            }
            return schActivity;
        }).collect(toList());
    }

    // Helper method to resolve a compound activity reference from its definition.
    private CompoundActivity resolveCompoundActivity(CriteriaContext context,
            Map<String, CompoundActivity> compoundActivityCache, Map<String, SchemaReference> schemaCache,
            Map<String, SurveyReference> surveyCache, CompoundActivity compoundActivity) {
        String taskId = compoundActivity.getTaskIdentifier();
        CompoundActivity resolvedCompoundActivity = compoundActivityCache.get(taskId);
        if (resolvedCompoundActivity == null) {
            if (compoundActivity.isReference()) {
                // Compound activity has no schemas or surveys defined. Resolve it with its definition.
                CompoundActivityDefinition compoundActivityDef = compoundActivityDefinitionService
                        .getCompoundActivityDefinition(context.getStudyIdentifier(), taskId);
                resolvedCompoundActivity = compoundActivityDef.getCompoundActivity();
            } else {
                // Compound activity has schemas and surveys defined. Use the schemas and surveys from the lists, but
                // we may need to resolve individual schema and survey refs at a later step.
                resolvedCompoundActivity = compoundActivity;
            }

            // Before we cache it, resolve the surveys and schemas in the list.
            resolvedCompoundActivity = resolveListsInCompoundActivity(context, schemaCache, surveyCache,
                    resolvedCompoundActivity);

            compoundActivityCache.put(taskId, resolvedCompoundActivity);
        }
        return resolvedCompoundActivity;
    }

    // Helper method to resolve schema refs and survey refs inside of a compound activity.
    private CompoundActivity resolveListsInCompoundActivity(CriteriaContext context,
            Map<String, SchemaReference> schemaCache, Map<String, SurveyReference> surveyCache,
            CompoundActivity compoundActivity) {
        // Resolve schemas.
        // Lists in CompoundActivity are always non-null, so we don't need to null-check.
        boolean isModified = false;
        List<SchemaReference> schemaList = new ArrayList<>();
        for (SchemaReference oneSchemaRef : compoundActivity.getSchemaList()) {
            SchemaReference resolvedSchemaRef = resolveSchema(context, schemaCache, oneSchemaRef);
            schemaList.add(resolvedSchemaRef);

            if (!resolvedSchemaRef.equals(oneSchemaRef)) {
                // Only mark the compound activity as dirty if resolution actually changed the schema.
                isModified = true;
            }
        }

        // Similarly, resolve surveys.
        List<SurveyReference> surveyList = new ArrayList<>();
        for (SurveyReference oneSurveyRef : compoundActivity.getSurveyList()) {
            SurveyReference resolvedSurveyRef = resolveSurvey(context, surveyCache, oneSurveyRef);
            surveyList.add(resolvedSurveyRef);

            if (!resolvedSurveyRef.equals(oneSurveyRef)) {
                isModified = true;
            }
        }

        if (!isModified) {
            // Resolution didn't change any of our schema and survey refs. Just return the compound activity as is.
            return compoundActivity;
        } else {
            // We need to make a new compound activity with the resolved schemas and surveys.
            return new CompoundActivity.Builder().copyOf(compoundActivity).withSchemaList(schemaList)
                    .withSurveyList(surveyList).build();
        }
    }

    // Helper method to resolve a schema ref to the latest revision for the client.
    private SchemaReference resolveSchema(CriteriaContext context, Map<String, SchemaReference> schemaCache,
            SchemaReference schemaRef) {
        if (schemaRef.getRevision() != null) {
            // Already has a revision. No need to resolve. Return as is.
            return schemaRef;
        }

        String schemaId = schemaRef.getId();
        SchemaReference resolvedSchemaRef = schemaCache.get(schemaId);
        if (resolvedSchemaRef == null) {
            UploadSchema schema = schemaService.getLatestUploadSchemaRevisionForAppVersion(
                    context.getStudyIdentifier(), schemaId, context.getClientInfo());
            resolvedSchemaRef = new SchemaReference(schemaId, schema.getRevision());
            schemaCache.put(schemaId, resolvedSchemaRef);
        }
        return resolvedSchemaRef;
    }

    // Helper method to resolve a published survey to a specific survey version.
    private SurveyReference resolveSurvey(CriteriaContext context, Map<String, SurveyReference> surveyCache,
            SurveyReference surveyRef) {
        if (surveyRef.getCreatedOn() != null) {
            return surveyRef;
        }

        String surveyGuid = surveyRef.getGuid();
        SurveyReference resolvedSurveyRef = surveyCache.get(surveyGuid);
        if (resolvedSurveyRef == null) {
            Survey survey = surveyService.getSurveyMostRecentlyPublishedVersion(context.getStudyIdentifier(),
                    surveyGuid);
            resolvedSurveyRef = new SurveyReference(survey.getIdentifier(), surveyGuid,
                    new DateTime(survey.getCreatedOn()));
            surveyCache.put(surveyGuid, resolvedSurveyRef);
        }
        return resolvedSurveyRef;
    }
}
