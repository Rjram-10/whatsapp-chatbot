import pandas as pd
import psycopg2

DB_CONFIG = {
    "dbname": "learn",
    "user": "postgres",
    "password": "postgres",
    "host": "localhost",
    "port": "5433"
}

DATASET_PATH = "dataset/"

conn = psycopg2.connect(**DB_CONFIG)
cur = conn.cursor()

# ── Step 1: Create symptom_severity lookup table ─────────────────────────────
cur.execute("""
    CREATE TABLE IF NOT EXISTS symptom_severity (
        symptom VARCHAR(100) PRIMARY KEY,
        weight  INTEGER NOT NULL
    );
""")

# ── Step 2: Add severity_score column to diseases table (if missing) ─────────
cur.execute("""
    ALTER TABLE diseases ADD COLUMN IF NOT EXISTS severity_score FLOAT;
""")

conn.commit()
print("Schema migration complete.")

# ── Step 3: Populate symptom_severity from CSV ───────────────────────────────
severity_df = pd.read_csv(f"{DATASET_PATH}Symptom-severity.csv")
inserted = 0
for _, row in severity_df.iterrows():
    symptom = str(row['Symptom']).strip().replace(' ', '_').lower()
    weight = int(row['weight'])
    cur.execute(
        "INSERT INTO symptom_severity (symptom, weight) VALUES (%s, %s) ON CONFLICT DO NOTHING",
        (symptom, weight)
    )
    inserted += 1

conn.commit()
print(f"Loaded {inserted} symptom severity weights.")

# ── Step 4: Pre-compute avg severity score per disease ───────────────────────
cur.execute("""
    UPDATE diseases de
    SET severity_score = sub.avg_weight
    FROM (
        SELECT de2.id,
               AVG(COALESCE(ss.weight, 3)) AS avg_weight
        FROM diseases de2
        CROSS JOIN LATERAL unnest(string_to_array(de2.symptoms, ', ')) AS s(symptom)
        LEFT JOIN symptom_severity ss ON ss.symptom = lower(trim(s.symptom))
        GROUP BY de2.id
    ) sub
    WHERE de.id = sub.id
""")

conn.commit()
cur.close()
conn.close()
print("Severity scores pre-computed and saved. Done!")
