"""Đẩy job + kỹ năng (kèm tổ tiên trong ontology) sang OpenSearch."""
from opensearchpy import OpenSearch, helpers
from app.common import conn, OS_URL

INDEX = "jobs"
MAPPING = {"mappings": {"properties": {
    "title": {"type": "text"}, "company": {"type": "keyword"}, "location": {"type": "keyword"},
    "salary_min": {"type": "double"}, "salary_max": {"type": "double"},
    "sources": {"type": "keyword"},
    "skills": {"type": "keyword"},          # skill trực tiếp
    "skills_expanded": {"type": "keyword"}, # + tất cả tổ tiên -> tìm theo skill cha
    "soft_skills": {"type": "keyword"},
    "hard_skills": {"type": "keyword"},
}}}


def client():
    return OpenSearch(OS_URL)


def reindex():
    os_ = client()
    if os_.indices.exists(INDEX):
        os_.indices.delete(INDEX)
    os_.indices.create(INDEX, body=MAPPING)

    with conn() as c, c.cursor() as cur:
        cur.execute("""
        SELECT j.job_id, j.title, co.name_display AS company, l.canonical AS location,
               j.salary_min, j.salary_max,
               array_remove(array_agg(DISTINCT ds.name), NULL) AS sources,
               array_remove(array_agg(DISTINCT s.pref_label), NULL) AS skills,
               array_remove(array_agg(DISTINCT s.pref_label) FILTER (WHERE s.skill_type='soft'), NULL) AS soft_skills,
               array_remove(array_agg(DISTINCT s.pref_label) FILTER (WHERE s.skill_type='hard'), NULL) AS hard_skills,
               array_remove(array_agg(DISTINCT anc.pref_label), NULL) AS skills_expanded
        FROM job j
        LEFT JOIN company co ON co.company_id=j.company_id
        LEFT JOIN location l ON l.location_id=j.location_id
        LEFT JOIN job_source_link jsl ON jsl.job_id=j.job_id
        LEFT JOIN data_source ds ON ds.source_id=jsl.source_id
        LEFT JOIN job_skill js ON js.job_id=j.job_id
        LEFT JOIN skill s ON s.skill_id=js.skill_id
        LEFT JOIN v_skill_descendant d ON d.skill_id=js.skill_id
        LEFT JOIN skill anc ON anc.skill_id=d.root_id
        WHERE j.is_current
        GROUP BY j.job_id, co.name_display, l.canonical""")
        docs = [{"_index": INDEX, "_id": r["job_id"], "_source": r} for r in cur.fetchall()]
    helpers.bulk(os_, docs)
    os_.indices.refresh(INDEX)
    return len(docs)
