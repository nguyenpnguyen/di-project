"""
4 wrapper minh hoạ 3 kiểu nguồn khác nhau nhưng cùng đổ về 1 global schema.
Thêm nguồn mới = thêm 1 lớp + FIELD_MAP, không sửa mediator (kiểu LAV).
"""
import csv, json, pathlib, requests
from app.wrappers.base import Wrapper

DATA = pathlib.Path(__file__).resolve().parents[2] / "data"


class VietJobsWrapper(Wrapper):
    """Dataset tiếng Việt ~48k tin, tải sẵn về data/vietjobs.csv."""
    name = "vietjobs"
    FIELD_MAP = {
        "external_id": ("id", "text"),
        "url": ("url", "text"),
        "title": ("job_title", "text"),
        "company": ("company_name", "text"),
        "location": ("location", "location"),
        "employment_type": ("employment_type", "text"),
        "salary": ("salary", "salary"),
        "exp_years_min": ("experience", "exp"),
        "description": ("description", "text"),
        "requirement": ("requirement", "text"),
        "skills_raw": ("skills", "list"),
        "posted_at": ("posted_date", "text"),
    }

    def fetch(self, limit=100, since=None, known=frozenset()):
        f = DATA / "vietjobs.csv"
        self.last_meta = {"stop_reason": "đọc từ file", "blocked": False}
        if not f.exists():
            self.last_meta["stop_reason"] = "chưa có data/vietjobs.csv"
            return []
        out = []
        with f.open(encoding="utf-8") as fh:
            for r in csv.DictReader(fh):
                if r.get("id") in known:                 # bỏ tin đã nạp
                    continue
                if since and (r.get("posted_date") or "") < str(since):
                    continue
                out.append(r)
                if len(out) >= limit:
                    self.last_meta["stop_reason"] = "đủ số tin yêu cầu"
                    break
        return out


class HFSkillWrapper(Wrapper):
    """HuggingFace job_skill_set: đã có sẵn skill -> dùng làm gold set benchmark."""
    name = "hf_job_skill"
    FIELD_MAP = {
        "external_id": ("idx", "text"),
        "url": ("link", "text"),
        "title": ("job_title", "text"),
        "company": ("company", "text"),
        "location": ("location", "location"),
        "employment_type": ("job_type", "text"),
        "salary": ("salary", "salary"),
        "exp_years_min": ("experience", "exp"),
        "description": ("description", "text"),
        "requirement": ("description", "text"),
        "skills_raw": ("job_skill_set", "list"),
        "posted_at": ("date", "text"),
    }

    def fetch(self, limit=100, since=None, known=frozenset()):
        f = DATA / "hf_job_skill.jsonl"
        self.last_meta = {"stop_reason": "đọc từ file", "blocked": False}
        if not f.exists():
            self.last_meta["stop_reason"] = "chưa có data/hf_job_skill.jsonl"
            return []
        out = []
        with f.open(encoding="utf-8") as fh:
            for i, line in enumerate(fh):
                r = json.loads(line)
                r.setdefault("idx", str(i))
                if r["idx"] in known:
                    continue
                if since and (r.get("date") or "") < str(since):
                    continue
                out.append(r)
                if len(out) >= limit:
                    self.last_meta["stop_reason"] = "đủ số tin yêu cầu"
                    break
        return out


class CrawlerWrapper(Wrapper):
    """Phần chung cho hai nguồn crawl: gọi hàm crawl, ghi lại lý do dừng, nuốt lỗi bị chặn."""
    start_page = 1

    def _run(self, fn, limit, since, known):
        from app.wrappers.crawler import CrawlBlocked
        try:
            recs, next_page, reason = fn(limit, since, known, self.start_page)
            self.last_meta = {"stop_reason": reason, "next_page": next_page, "blocked": False}
            return recs
        except CrawlBlocked as e:
            self.last_meta = {"stop_reason": str(e), "next_page": self.start_page,
                              "blocked": True, "retry_after": e.retry_after}
            return e.partial        # vẫn giữ phần đã lấy được


class TopCVWrapper(CrawlerWrapper):
    """Crawler HTML. Tên trường ở nguồn khác hẳn -> minh hoạ schema matching."""
    name = "topcv"
    FIELD_MAP = {
        "external_id": ("job_id", "text"),
        "url": ("detail_url", "text"),
        "title": ("title", "text"),
        "company": ("company_name", "text"),
        "location": ("work_place", "location"),
        "employment_type": ("job_type", "text"),
        "salary": ("salary_text", "salary"),
        "exp_years_min": ("experience_text", "exp"),
        "description": ("job_description", "text"),
        "requirement": ("candidate_requirement", "text"),
        "skills_raw": ("tags", "list"),
        "posted_at": ("created_date", "text"),
    }

    def fetch(self, limit=100, since=None, known=frozenset()):
        from app.wrappers.crawler import crawl_topcv
        return self._run(crawl_topcv, limit, since, known)


class Vieclam24hWrapper(CrawlerWrapper):
    """Cùng ý nghĩa, tên trường khác: job_name/noi_lam_viec/thu_nhap."""
    name = "vieclam24h"
    FIELD_MAP = {
        "external_id": ("id", "text"),
        "url": ("link", "text"),
        "title": ("job_name", "text"),
        "company": ("employer", "text"),
        "location": ("noi_lam_viec", "location"),
        "employment_type": ("hinh_thuc", "text"),
        "salary": ("thu_nhap", "salary"),
        "exp_years_min": ("kinh_nghiem", "exp"),
        "description": ("mo_ta", "text"),
        "requirement": ("yeu_cau", "text"),
        "skills_raw": ("ky_nang", "list"),
        "posted_at": ("ngay_dang", "text"),
    }

    def fetch(self, limit=100, since=None, known=frozenset()):
        from app.wrappers.crawler import crawl_vieclam24h
        return self._run(crawl_vieclam24h, limit, since, known)


REGISTRY = {w.name: w() for w in [VietJobsWrapper, HFSkillWrapper, TopCVWrapper, Vieclam24hWrapper]}
