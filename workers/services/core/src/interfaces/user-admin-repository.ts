export interface UserAdminSearch {
  offset: number;
  limit: number;
  keyword: string;
}

export interface UserAdminRecord {
  id: number;
  username: string;
  nickname: string | null;
  email: string | null;
  role: string;
  status: number;
  createdAt: string;
}

export interface UserAdminRepository {
  searchUsers(query: UserAdminSearch): Promise<UserAdminRecord[]>;
  countUsers(keyword: string): Promise<number>;
  findUserRole(userId: number): Promise<string | null>;
  updateStatus(userId: number, status: 0 | 1): Promise<void>;
  findUser(userId: number): Promise<UserAdminRecord | null>;
}
