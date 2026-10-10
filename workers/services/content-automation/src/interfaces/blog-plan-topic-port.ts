export interface BlogPlanTopicInput {
  seed: string;
  count: number;
  audience?: string;
  tone?: string;
  excludeTitles: string[];
}

export interface BlogPlanTopicPort {
  generateTopics(userId: number, input: BlogPlanTopicInput): Promise<string[]>;
}
