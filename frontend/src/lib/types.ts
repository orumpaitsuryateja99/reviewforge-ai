export type User = { id: string; login: string; avatarUrl?: string };
export type Session = { configured: boolean; authenticated: boolean; user?: User };

export type Capabilities = {
  github: boolean;
  review: { configured: boolean; provider: string; model: string | null };
  testRunner: boolean;
};

export type Repository = {
  id: string;
  githubRepositoryId: number;
  fullName: string;
  defaultBranch: string;
  privateRepository: boolean;
  testExecutionAllowed: boolean;
};

export type PullRequest = {
  id: string;
  repositoryId: string;
  number: number;
  title: string;
  authorLogin: string;
  state: string;
  headSha: string;
  updatedAt: string;
};

export type ChangedFile = {
  path: string;
  previousPath?: string;
  status: string;
  additions: number;
  deletions: number;
  patch: string;
  patchTruncated: boolean;
};

export type PullRequestDetail = PullRequest & {
  baseRef: string;
  baseSha: string;
  headRef: string;
  files: ChangedFile[];
  filesTruncated: boolean;
};

export type AnalysisStatus =
  | "QUEUED"
  | "BUILDING_CONTEXT"
  | "ANALYZING"
  | "VALIDATING"
  | "COMPLETED"
  | "FAILED"
  | "STALE"
  | "CANCELLED";

export type Analysis = {
  id: string;
  pullRequestId: string;
  headSha: string;
  currentHeadSha: string;
  status: AnalysisStatus;
  progressPercent: number;
  stale: boolean;
  findingCount: number;
  contextFileCount: number;
  llmProvider?: string;
  llmModel?: string;
  error?: { code: string; message: string };
  createdAt: string;
  startedAt?: string;
  completedAt?: string;
};

export type Severity = "CRITICAL" | "HIGH" | "MEDIUM" | "LOW";

export type Finding = {
  id: string;
  analysisId: string;
  category: string;
  severity: Severity;
  title: string;
  explanation: string;
  filePath: string;
  startLine: number;
  endLine: number;
  evidence: string;
  failureScenario: string;
  suggestedFix: string;
  confidence: number;
};

export type RejectedFinding = {
  reasonCode: string;
  reasonDetail: string;
  filePath?: string;
  startLine?: number;
  endLine?: number;
};

export type GeneratedTestStatus =
  | "GENERATING"
  | "PROPOSED"
  | "APPROVED"
  | "REJECTED"
  | "FAILED"
  | "STALE";

export type GeneratedTest = {
  id: string;
  findingId: string;
  analysisId: string;
  status: GeneratedTestStatus;
  targetFilePath: string;
  unifiedDiff: string;
  fileContent?: string;
  rationale: string;
  headSha?: string;
  errorMessage?: string;
  approvedAt?: string;
  createdAt: string;
};

export type TestRunStatus =
  | "QUEUED"
  | "PREPARING"
  | "RUNNING"
  | "PASSED"
  | "FAILED"
  | "TIMED_OUT"
  | "INFRASTRUCTURE_ERROR"
  | "CANCELLED";

export type TestRun = {
  id: string;
  generatedTestId: string;
  status: TestRunStatus;
  commandProfile: string;
  exitCode?: number;
  testsRun?: number;
  testsPassed?: number;
  testsFailed?: number;
  testsSkipped?: number;
  stdout?: string;
  stderr?: string;
  timedOut: boolean;
  durationMillis?: number;
  attempts: number;
  startedAt?: string;
  completedAt?: string;
  createdAt: string;
};

export type ReviewReport = {
  analysis: Analysis;
  repositoryFullName: string;
  pullRequestNumber: number;
  pullRequestTitle: string;
  findingsBySeverity: Record<string, number>;
  findings: Finding[];
  rejectedFindings: RejectedFinding[];
  generatedTests: GeneratedTest[];
  testRuns: TestRun[];
  generatedAt: string;
};

export type JobAccepted = { jobId: string; status: string; statusUrl: string };

export const ANALYSIS_TERMINAL: AnalysisStatus[] = ["COMPLETED", "FAILED", "STALE", "CANCELLED"];
export const RUN_TERMINAL: TestRunStatus[] = [
  "PASSED",
  "FAILED",
  "TIMED_OUT",
  "INFRASTRUCTURE_ERROR",
  "CANCELLED",
];
