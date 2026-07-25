"""
Nạp ontology kỹ năng.
- Chế độ thật: tải ESCO CSV (skills_en.csv, broaderRelationsSkillPillar.csv) vào data/esco/
- Chế độ offline: dùng SEED bên dưới (đủ để demo phân cấp cứng/mềm).
"""
import csv, pathlib
from app.common import conn, norm

DATA = pathlib.Path(__file__).resolve().parents[2] / "data"

# (pref_label, type, cha, [alias])
SEED = [
    ("Programming", "hard", None, ["lap trinh", "coding", "software development"]),
    ("Python", "hard", "Programming", ["python3", "py", "python programming", "python developer"]),
    ("Java", "hard", "Programming", ["core java", "java se", "j2ee"]),
    ("JavaScript", "hard", "Programming", ["js", "es6", "javascript es6"]),
    ("Go", "hard", "Programming", ["golang"]),
    ("Web Development", "hard", "Programming", ["lap trinh web"]),
    ("React", "hard", "Web Development", ["reactjs", "react.js"]),
    ("Spring", "hard", "Java", ["spring boot", "springboot"]),
    ("Data Engineering", "hard", None, ["ky su du lieu"]),
    ("SQL", "hard", "Data Engineering", ["t-sql", "postgresql", "mysql", "sql server"]),
    ("Apache Spark", "hard", "Data Engineering", ["spark", "pyspark"]),
    ("Apache Kafka", "hard", "Data Engineering", ["kafka"]),
    ("Airflow", "hard", "Data Engineering", ["apache airflow"]),
    ("Machine Learning", "hard", None, ["ml", "hoc may"]),
    ("Deep Learning", "hard", "Machine Learning", ["dl", "hoc sau"]),
    ("TensorFlow", "hard", "Deep Learning", ["tf", "keras"]),
    ("PyTorch", "hard", "Deep Learning", ["torch"]),
    ("Scikit-learn", "hard", "Machine Learning", ["sklearn"]),
    ("DevOps", "hard", None, []),
    ("Docker", "hard", "DevOps", ["container", "docker compose"]),
    ("Kubernetes", "hard", "DevOps", ["k8s"]),
    ("AWS", "hard", "DevOps", ["amazon web services", "ec2", "s3"]),
    ("Communication", "soft", None, ["giao tiep", "communication skills"]),
    ("Presentation", "soft", "Communication", ["thuyet trinh"]),
    ("Negotiation", "soft", "Communication", ["dam phan"]),
    ("Teamwork", "soft", "Communication", ["lam viec nhom", "team work", "collaboration"]),
    ("Leadership", "soft", None, ["lanh dao", "quan ly doi nhom", "team lead"]),
    ("Problem Solving", "soft", None, ["giai quyet van de", "critical thinking"]),
    ("Time Management", "soft", None, ["quan ly thoi gian"]),
    ("English", "soft", "Communication", ["tieng anh", "toeic", "ielts"]),
]


def _upsert_skill(cur, label, stype, uri=None, ont="seed"):
    cur.execute("""INSERT INTO skill(pref_label, skill_type, concept_uri, source_ont)
                   VALUES (%s,%s,%s,%s) ON CONFLICT (concept_uri) DO NOTHING""",
                (label, stype, uri or f"seed:{norm(label)}", ont))
    cur.execute("SELECT skill_id FROM skill WHERE concept_uri=%s", (uri or f"seed:{norm(label)}",))
    return cur.fetchone()["skill_id"]


def _alias(cur, sid, text):
    cur.execute("""INSERT INTO skill_alias(skill_id, alias, alias_norm) VALUES (%s,%s,%s)
                   ON CONFLICT DO NOTHING""", (sid, text, norm(text)))


def load_seed():
    with conn() as c, c.cursor() as cur:
        ids = {}
        for label, stype, parent, aliases in SEED:
            sid = _upsert_skill(cur, label, stype)
            ids[label] = sid
            _alias(cur, sid, label)
            for a in aliases:
                _alias(cur, sid, a)
        for label, _, parent, _a in SEED:
            if parent:
                cur.execute("""INSERT INTO skill_relation(parent_id, child_id, rel_type)
                               VALUES (%s,%s,'broader') ON CONFLICT DO NOTHING""",
                            (ids[parent], ids[label]))
        cur.execute("""UPDATE skill s SET level = (
            SELECT count(*) FROM skill_relation r WHERE r.child_id = s.skill_id)""")
    return len(SEED)


def load_esco():
    """ESCO: skills_en.csv (conceptUri, preferredLabel, altLabels, skillType)
       + broaderRelationsSkillPillar.csv (conceptUri, broaderUri)."""
    d = DATA / "esco"
    if not (d / "skills_en.csv").exists():
        return 0
    with conn() as c, c.cursor() as cur, (d / "skills_en.csv").open(encoding="utf-8") as f:
        uri2id = {}
        for r in csv.DictReader(f):
            stype = "soft" if "transversal" in (r.get("skillType", "") + r.get("conceptType", "")).lower() else "hard"
            sid = _upsert_skill(cur, r["preferredLabel"], stype, r["conceptUri"], "esco")
            uri2id[r["conceptUri"]] = sid
            _alias(cur, sid, r["preferredLabel"])
            for a in (r.get("altLabels") or "").split("\n"):
                if a.strip():
                    _alias(cur, sid, a.strip())
        rel = d / "broaderRelationsSkillPillar.csv"
        if rel.exists():
            with rel.open(encoding="utf-8") as fr:
                for r in csv.DictReader(fr):
                    p, ch = uri2id.get(r["broaderUri"]), uri2id.get(r["conceptUri"])
                    if p and ch:
                        cur.execute("""INSERT INTO skill_relation VALUES (%s,%s,'broader')
                                       ON CONFLICT DO NOTHING""", (p, ch))
        return len(uri2id)


if __name__ == "__main__":
    print("seed:", load_seed(), "| esco:", load_esco())
