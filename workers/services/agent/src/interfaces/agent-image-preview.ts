/** Transient image delivery only: never persist inline binary data in D1. */
export interface AgentImagePreview {
  url: string;
  title: string;
  contentType: string;
  storageStatus: "saving";
}
