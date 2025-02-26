/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.scheduler.persistence;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import io.airbyte.analytics.TrackingClient;
import io.airbyte.commons.map.MoreMaps;
import io.airbyte.config.Notification;
import io.airbyte.config.Notification.NotificationType;
import io.airbyte.config.StandardDestinationDefinition;
import io.airbyte.config.StandardSourceDefinition;
import io.airbyte.config.StandardWorkspace;
import io.airbyte.config.persistence.ConfigRepository;
import io.airbyte.notification.NotificationClient;
import io.airbyte.scheduler.models.Job;
import io.airbyte.scheduler.persistence.job_tracker.TrackingMetadata;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JobNotifier {

  private static final Logger LOGGER = LoggerFactory.getLogger(JobNotifier.class);

  public static final String FAILURE_NOTIFICATION = "Failure Notification";
  public static final String SUCCESS_NOTIFICATION = "Success Notification";
  public static final String CONNECTION_DISABLED_WARNING_NOTIFICATION = "Connection Disabled Warning Notification";
  public static final String CONNECTION_DISABLED_NOTIFICATION = "Connection Disabled Notification";

  private final String connectionPageUrl;
  private final ConfigRepository configRepository;
  private final TrackingClient trackingClient;
  private final WorkspaceHelper workspaceHelper;

  public JobNotifier(final String webappUrl,
                     final ConfigRepository configRepository,
                     final WorkspaceHelper workspaceHelper,
                     final TrackingClient trackingClient) {
    this.workspaceHelper = workspaceHelper;
    if (webappUrl.endsWith("/")) {
      this.connectionPageUrl = String.format("%sconnections/", webappUrl);
    } else {
      this.connectionPageUrl = String.format("%s/connections/", webappUrl);
    }
    this.configRepository = configRepository;
    this.trackingClient = trackingClient;
  }

  private void notifyJob(final String reason, final String action, final Job job) {
    try {
      final UUID workspaceId = workspaceHelper.getWorkspaceForJobIdIgnoreExceptions(job.getId());
      final StandardWorkspace workspace = configRepository.getStandardWorkspace(workspaceId, true);
      notifyJob(reason, action, job, workspaceId, workspace, workspace.getNotifications());
    } catch (final Exception e) {
      LOGGER.error("Unable to read configuration:", e);
    }
  }

  private void notifyJob(final String reason,
                         final String action,
                         final Job job,
                         final UUID workspaceId,
                         final StandardWorkspace workspace,
                         final List<Notification> notifications) {
    final UUID connectionId = UUID.fromString(job.getScope());
    try {
      final StandardSourceDefinition sourceDefinition = configRepository.getSourceDefinitionFromConnection(connectionId);
      final StandardDestinationDefinition destinationDefinition = configRepository.getDestinationDefinitionFromConnection(connectionId);
      final Instant jobStartedDate = Instant.ofEpochSecond(job.getStartedAtInSecond().orElse(job.getCreatedAtInSecond()));
      final DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL).withZone(ZoneId.systemDefault());
      final Instant jobUpdatedDate = Instant.ofEpochSecond(job.getUpdatedAtInSecond());
      final Instant adjustedJobUpdatedDate = jobUpdatedDate.equals(jobStartedDate) ? Instant.now() : jobUpdatedDate;
      final Duration duration = Duration.between(jobStartedDate, adjustedJobUpdatedDate);
      final String durationString = formatDurationPart(duration.toDaysPart(), "day")
          + formatDurationPart(duration.toHoursPart(), "hour")
          + formatDurationPart(duration.toMinutesPart(), "minute")
          + formatDurationPart(duration.toSecondsPart(), "second");
      final String sourceConnector = String.format("%s version %s", sourceDefinition.getName(), sourceDefinition.getDockerImageTag());
      final String destinationConnector = String.format("%s version %s", destinationDefinition.getName(), destinationDefinition.getDockerImageTag());
      final String failReason = Strings.isNullOrEmpty(reason) ? "" : String.format(", as the %s", reason);
      final String jobDescription =
          String.format("sync started on %s, running for%s%s.", formatter.format(jobStartedDate), durationString, failReason);
      final String logUrl = connectionPageUrl + connectionId;
      final ImmutableMap<String, Object> jobMetadata = TrackingMetadata.generateJobAttemptMetadata(job);
      final ImmutableMap<String, Object> sourceMetadata = TrackingMetadata.generateSourceDefinitionMetadata(sourceDefinition);
      final ImmutableMap<String, Object> destinationMetadata = TrackingMetadata.generateDestinationDefinitionMetadata(destinationDefinition);
      for (final Notification notification : notifications) {
        final NotificationClient notificationClient = getNotificationClient(notification);
        try {
          final Builder<String, Object> notificationMetadata = ImmutableMap.builder();
          notificationMetadata.put("connection_id", connectionId);
          if (notification.getNotificationType().equals(NotificationType.SLACK) &&
              notification.getSlackConfiguration().getWebhook().contains("hooks.slack.com")) {
            // flag as slack if the webhook URL is also pointing to slack
            notificationMetadata.put("notification_type", NotificationType.SLACK);
          } else if (notification.getNotificationType().equals(NotificationType.CUSTOMERIO)) {
            notificationMetadata.put("notification_type", NotificationType.CUSTOMERIO);
          } else {
            // Slack Notification type could be "hacked" and re-used for custom webhooks
            notificationMetadata.put("notification_type", "N/A");
          }
          trackingClient.track(
              workspaceId,
              action,
              MoreMaps.merge(jobMetadata, sourceMetadata, destinationMetadata, notificationMetadata.build()));
          if (FAILURE_NOTIFICATION.equals(action)) {
            if (!notificationClient.notifyJobFailure(sourceConnector, destinationConnector, jobDescription, logUrl)) {
              LOGGER.warn("Failed to successfully notify failure: {}", notification);
            }
          } else if (SUCCESS_NOTIFICATION.equals(action)) {
            if (!notificationClient.notifyJobSuccess(sourceConnector, destinationConnector, jobDescription, logUrl)) {
              LOGGER.warn("Failed to successfully notify success: {}", notification);
            }
            // alert message currently only supported by email through customer.io
          } else if (CONNECTION_DISABLED_NOTIFICATION.equals(action) && notification.getNotificationType().equals(NotificationType.CUSTOMERIO)) {
            if (!notificationClient.notifyConnectionDisabled(workspace.getEmail(), sourceConnector, destinationConnector, jobDescription, logUrl)) {
              LOGGER.warn("Failed to successfully notify auto-disable connection: {}", notification);
            }
          } else if (CONNECTION_DISABLED_WARNING_NOTIFICATION.equals(action)
              && notification.getNotificationType().equals(NotificationType.CUSTOMERIO)) {
            if (!notificationClient.notifyConnectionDisabled(workspace.getEmail(), sourceConnector, destinationConnector, jobDescription, logUrl)) {
              LOGGER.warn("Failed to successfully notify auto-disable connection warning: {}", notification);
            }
          }
        } catch (final Exception e) {
          LOGGER.error("Failed to notify: {} due to an exception", notification, e);
        }
      }
    } catch (final Exception e) {
      LOGGER.error("Unable to read configuration:", e);
    }
  }

  public void notifyJobByEmail(final String reason, final String action, final Job job) {
    final Notification emailNotification = new Notification();
    emailNotification.setNotificationType(NotificationType.CUSTOMERIO);
    try {
      final UUID workspaceId = workspaceHelper.getWorkspaceForJobIdIgnoreExceptions(job.getId());
      final StandardWorkspace workspace = configRepository.getStandardWorkspace(workspaceId, true);
      notifyJob(reason, action, job, workspaceId, workspace, Collections.singletonList(emailNotification));
    } catch (final Exception e) {
      LOGGER.error("Unable to read configuration:", e);
    }
  }

  public void failJob(final String reason, final Job job) {
    notifyJob(reason, FAILURE_NOTIFICATION, job);
  }

  public void successJob(final Job job) {
    notifyJob(null, SUCCESS_NOTIFICATION, job);
  }

  protected NotificationClient getNotificationClient(final Notification notification) {
    return NotificationClient.createNotificationClient(notification);
  }

  private static String formatDurationPart(final long durationPart, final String timeUnit) {
    if (durationPart == 1) {
      return String.format(" %s %s", durationPart, timeUnit);
    } else if (durationPart > 1) {
      // Use plural timeUnit
      return String.format(" %s %ss", durationPart, timeUnit);
    }
    return "";
  }

}
