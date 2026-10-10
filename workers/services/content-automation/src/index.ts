import { createBusinessService, type WorkerExecutionContextLike } from "@dinqor/worker-runtime";
import { handleBlogPublicRequest } from "./controllers/blog-public-controller.js";
import { handleBlogPostCreationRequest } from "./controllers/blog-post-creation-controller.js";
import { handleExternalPublicationRequest } from "./controllers/external-publication-controller.js";
import { handleContentNotificationRequest } from "./controllers/content-notification-controller.js";
import { handleBlogPlanRequest } from "./controllers/blog-plan-controller.js";
import { handleBlogAgentTaskRequest } from "./controllers/blog-agent-task-controller.js";
import { handleBlogManagementRequest } from "./controllers/blog-management-controller.js";
import { handleEmailReminderRequest } from "./controllers/email-reminder-controller.js";
import { handleBlogMediaRequest } from "./controllers/blog-media-controller.js";
import { handleBlogMcpRequest } from "./controllers/blog-mcp-controller.js";
import type { ContentEnvironment } from "./interfaces/content-environment.js";
import { D1BlogPublicMapper } from "./mappers/d1-blog-public-mapper.js";
import { D1BlogPostCreationMapper } from "./mappers/d1-blog-post-creation-mapper.js";
import { D1ContentPublicationMapper } from "./mappers/d1-content-publication-mapper.js";
import { D1ContentNotificationMapper } from "./mappers/d1-content-notification-mapper.js";
import { D1BlogPlanMapper } from "./mappers/d1-blog-plan-mapper.js";
import { D1BlogAgentTaskMapper } from "./mappers/d1-blog-agent-task-mapper.js";
import { D1BlogManagementMapper } from "./mappers/d1-blog-management-mapper.js";
import { D1EmailReminderMapper } from "./mappers/d1-email-reminder-mapper.js";
import { D1BlogMediaMapper } from "./mappers/d1-blog-media-mapper.js";
import { OssBlogMediaMapper } from "./mappers/oss-blog-media-mapper.js";
import { PlatformReminderMailMapper } from "./mappers/platform-reminder-mail-mapper.js";
import { CoreEmailReminderBillingMapper } from "./mappers/core-email-reminder-billing-mapper.js";
import { CoreBlogPlanMaterialMapper } from "./mappers/core-blog-plan-material-mapper.js";
import { CoreBlogPlanTopicMapper } from "./mappers/core-blog-plan-topic-mapper.js";
import { CoreBlogPlanModelMapper } from "./mappers/core-blog-plan-model-mapper.js";
import { OssArticleContentMapper } from "./mappers/oss-article-content-mapper.js";
import { PlatformIntegrationPublicationMapper } from "./mappers/platform-integration-publication-mapper.js";
import { BlogPublicService } from "./services/blog-public-service.js";
import { ExternalPublicationService } from "./services/external-publication-service.js";
import { BlogPostCreationService } from "./services/blog-post-creation-service.js";
import { ContentNotificationService } from "./services/content-notification-service.js";
import { BlogPlanService } from "./services/blog-plan-service.js";
import { BlogPlanExecutionService } from "./services/blog-plan-execution-service.js";
import { BlogAgentTaskService } from "./services/blog-agent-task-service.js";
import { BlogManagementService } from "./services/blog-management-service.js";
import { EmailReminderService } from "./services/email-reminder-service.js";
import { BlogMediaService } from "./services/blog-media-service.js";
import { BlogMcpService } from "./services/blog-mcp-service.js";

const contentService = createBusinessService<ContentEnvironment>("content-automation", async (request, env, context) => {
  const contentReader = new OssArticleContentMapper(env);
  const postCreationService = new BlogPostCreationService(
    new D1BlogPostCreationMapper(env.DB), contentReader,
  );
  const creationResponse = await handleBlogPostCreationRequest(request, postCreationService);
  if (creationResponse) return creationResponse;

  const blogManagementServiceForMcp = new BlogManagementService(new D1BlogManagementMapper(env.DB), contentReader);
  const blogMcpResponse = await handleBlogMcpRequest(
    request, new BlogMcpService(postCreationService, blogManagementServiceForMcp),
  );
  if (blogMcpResponse) return blogMcpResponse;

  const publicationService = new ExternalPublicationService(
    new D1ContentPublicationMapper(env.DB),
    contentReader,
    new PlatformIntegrationPublicationMapper(env),
  );
  const publicationResponse = await handleExternalPublicationRequest(request, publicationService);
  if (publicationResponse) return publicationResponse;

  const notifications = new ContentNotificationService(new D1ContentNotificationMapper(env.DB));
  const notificationResponse = await handleContentNotificationRequest(request, notifications);
  if (notificationResponse) return notificationResponse;

  const blogMedia = new BlogMediaService(new D1BlogMediaMapper(env.DB), new OssBlogMediaMapper(env));
  const mediaResponse = await handleBlogMediaRequest(request, blogMedia);
  if (mediaResponse) return mediaResponse;

  const planMapper = new D1BlogPlanMapper(env.DB);
  const planMaterials = new CoreBlogPlanMaterialMapper(env);
  const plans = new BlogPlanService(planMapper, {
    materials: planMaterials,
    topics: new CoreBlogPlanTopicMapper(env),
  });
  const planExecution = new BlogPlanExecutionService(
    planMapper,
    new CoreBlogPlanModelMapper(env),
    postCreationService,
    publicationService,
    notifications,
    planMaterials,
  );
  const planResponse = await handleBlogPlanRequest(request, plans, planExecution, context);
  if (planResponse) return planResponse;

  const blogPublicService = new BlogPublicService(new D1BlogPublicMapper(env.DB), contentReader);
  const blogManagementService = blogManagementServiceForMcp;
  const managementResponse = await handleBlogManagementRequest(request, blogManagementService);
  if (managementResponse) return managementResponse;

  const reminderService = new EmailReminderService(
    new D1EmailReminderMapper(env.DB),
    new PlatformReminderMailMapper(env),
    new CoreEmailReminderBillingMapper(env),
    {
      senderEmail: env.MAIL_FROM,
      minimumLeadSeconds: Number(env.EMAIL_REMINDER_MINIMUM_LEAD_SECONDS) || 30,
      displayZone: env.EMAIL_REMINDER_DISPLAY_ZONE ?? "Asia/Shanghai",
    },
  );
  const reminderResponse = await handleEmailReminderRequest(request, reminderService);
  if (reminderResponse) return reminderResponse;

  const blogAgentTasks = new BlogAgentTaskService(
    new D1BlogAgentTaskMapper(env.DB),
    new CoreBlogPlanModelMapper(env),
    postCreationService,
    blogPublicService,
    contentReader,
  );
  const blogAgentResponse = await handleBlogAgentTaskRequest(request, env, blogAgentTasks, context);
  if (blogAgentResponse) return blogAgentResponse;
  return handleBlogPublicRequest(request, blogPublicService);
});

interface ScheduledControllerLike {
  scheduledTime: number;
  cron: string;
  noRetry(): void;
}

export default {
  ...contentService,
  async scheduled(
    controller: ScheduledControllerLike,
    env: ContentEnvironment,
    context?: WorkerExecutionContextLike,
  ): Promise<void> {
    const repo = new D1BlogPlanMapper(env.DB);
    const materials = new CoreBlogPlanMaterialMapper(env);
    const contentReader = new OssArticleContentMapper(env);
    const posts = new BlogPostCreationService(new D1BlogPostCreationMapper(env.DB), contentReader);
    const publication = new ExternalPublicationService(
      new D1ContentPublicationMapper(env.DB),
      contentReader,
      new PlatformIntegrationPublicationMapper(env),
    );
    const notifications = new ContentNotificationService(new D1ContentNotificationMapper(env.DB));
    const planExecution = new BlogPlanExecutionService(
      repo, new CoreBlogPlanModelMapper(env), posts, publication, notifications, materials,
    );
    const reminders = new EmailReminderService(
      new D1EmailReminderMapper(env.DB),
      new PlatformReminderMailMapper(env),
      new CoreEmailReminderBillingMapper(env),
      {
        senderEmail: env.MAIL_FROM,
        minimumLeadSeconds: Number(env.EMAIL_REMINDER_MINIMUM_LEAD_SECONDS) || 30,
        displayZone: env.EMAIL_REMINDER_DISPLAY_ZONE ?? "Asia/Shanghai",
      },
    );
    const work = Promise.all([
      new D1BlogPublicMapper(env.DB).cleanupExpiredViewDedup(new Date(controller.scheduledTime).toISOString()),
      planExecution.processDue(controller.scheduledTime),
      reminders.processDue(new Date(controller.scheduledTime)),
      notifications.cleanupExpiredStreams(controller.scheduledTime),
    ]);
    if (context) context.waitUntil(work);
    else await work;
  },
};
