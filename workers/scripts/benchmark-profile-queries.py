"""Local SQLite query-plan regression and latency probe; never connects to production."""
import json
import re
import sqlite3
import statistics
import time
from pathlib import Path

root = Path(__file__).resolve().parents[1]
db = sqlite3.connect(":memory:")
db.executescript((root / "services/content-automation/migrations/0001_publishing.sql").read_text(encoding="utf-8"))
db.executescript((root / "services/content-automation/migrations/0005_blog_post_create_idempotency.sql").read_text(encoding="utf-8"))
db.executemany("INSERT INTO blog_post(title,summary,content_inline,category_id,user_id,status,updated_at) VALUES ('title','summary','body','backend',?,?,?)", ((n % 10, n % 3 - 1, f"2026-10-{n % 28 + 1:02d}T00:00:00.000Z") for n in range(100000)))
queries = {
    "managed_count": ("SELECT COUNT(*) FROM blog_post WHERE user_id = ? AND status IN (0,1)", (2,)),
    "managed_page": ("SELECT id FROM blog_post WHERE user_id = ? AND status IN (0,1) ORDER BY COALESCE(published_at,updated_at) DESC,id DESC LIMIT 20", (2,)),
    "status_page": ("SELECT id FROM blog_post WHERE user_id = ? AND status = ? ORDER BY COALESCE(published_at,updated_at) DESC,id DESC LIMIT 20", (2, 1)),
}
def measure(sql, args=()):
    times = []
    result = db.execute(sql,args).fetchall()
    for _ in range(20):
        start = time.perf_counter()
        assert db.execute(sql,args).fetchall() == result
        times.append((time.perf_counter()-start)*1000)
    return {"median_ms": round(statistics.median(times),3), "plan": [r[3] for r in db.execute("EXPLAIN QUERY PLAN " + sql,args)]}, result
before = {name: measure(*query) for name,query in queries.items()}
db.executescript((root / "services/content-automation/migrations/0015_profile_query_indexes.sql").read_text(encoding="utf-8"))
report = {}
for name,query in queries.items():
    after, result = measure(*query)
    assert result == before[name][1]
    assert all("SCAN blog_post" not in step for step in after["plan"])
    if name == "status_page": assert all("TEMP B-TREE" not in step for step in after["plan"])
    report[name] = {"before": before[name][0], "after": after}
db.executescript("CREATE TABLE points_ledger(id INTEGER PRIMARY KEY,user_id INTEGER,biz_type TEXT,amount INTEGER,created_at TEXT); CREATE INDEX idx_ledger_time ON points_ledger(user_id,created_at);")
db.executemany("INSERT INTO points_ledger VALUES (?,1,'consume',1,?)", ((n,f"2026-01-{n % 28+1:02d}T00:00:00.000Z") for n in range(100000)))
db.execute("INSERT INTO points_ledger VALUES (100001,1,'consume',7,strftime('%Y-%m-%dT00:00:00.000Z','now'))")
old = "SELECT COALESCE(SUM(amount),0) FROM points_ledger WHERE user_id=1 AND biz_type <> 'grant' AND date(created_at)=date('now')"
mapper = (root / "services/core/src/mappers/d1-billing-mapper.ts").read_text(encoding="utf-8")
new = re.search(r"SELECT COALESCE\(SUM\(amount\), 0\) AS consumedToday.*?`", mapper, re.S).group(0)[:-1].replace("?", "1")
old_t, old_result = measure(old)
new_t, new_result = measure(new)
assert old_result == new_result == [(7,)]
assert any("created_at>" in step for step in new_t["plan"])
report["today_points"] = {"before":old_t,"after":new_t}
print(json.dumps(report,ensure_ascii=False,indent=2))
