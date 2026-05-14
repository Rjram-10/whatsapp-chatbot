import pandas as pd
import psycopg2
import numpy as np
import requests
import json

# ── Config ──────────────────────────────────────────────
DB_CONFIG = {
    "dbname": "learn",
    "user": "postgres",
    "password": "postgres",
    "host": "localhost",
    "port": "5433"
}
DATASET_PATH = "dataset/"
# ────────────────────────────────────────────────────────

def setup_database(cur):
    """Create pgvector extension and table"""
    cur.execute("SELECT version();")
    print(cur.fetchone())
    cur.execute("CREATE EXTENSION IF NOT EXISTS vector;")
    # Drop legacy table name (from old schema) and new table
    cur.execute("DROP TABLE IF EXISTS disease_embeddings CASCADE;")
    cur.execute("DROP TABLE IF EXISTS diseases CASCADE;")
    cur.execute("""
        CREATE TABLE diseases (
            id SERIAL PRIMARY KEY,
            name VARCHAR(200) NOT NULL,
            symptoms TEXT,
            description TEXT,
            precautions TEXT,
            combined_text TEXT,
            embedding vector(768),
            severity_score FLOAT
        );
    """)
    cur.execute("""
        CREATE INDEX IF NOT EXISTS disease_embedding_idx 
        ON diseases 
        USING ivfflat (embedding vector_cosine_ops)
        WITH (lists = 10);
    """)
    print("Database setup complete")

def load_datasets():
    """Load all CSV files from Kaggle dataset"""
    try:
        symptoms_df = pd.read_csv(f"{DATASET_PATH}dataset.csv")
        descriptions_df = pd.read_csv(f"{DATASET_PATH}symptom_Description.csv")
        precautions_df = pd.read_csv(f"{DATASET_PATH}symptom_precaution.csv")
        severity_df = pd.read_csv(f"{DATASET_PATH}Symptom-severity.csv")
        print(f"Loaded {len(descriptions_df)} diseases")
        print(f"Loaded {len(severity_df)} symptom severity records")
        return symptoms_df, descriptions_df, precautions_df, severity_df
    except FileNotFoundError as e:
        print(f"Dataset file not found: {e}")
        print("Make sure you downloaded and extracted the Kaggle dataset")
        raise

def get_symptoms_for_disease(disease, symptoms_df):
    """Extract symptom list for a disease"""
    disease_rows = symptoms_df[symptoms_df['Disease'] == disease]
    if disease_rows.empty:
        return ""
    
    symptoms = []
    for col in symptoms_df.columns[1:]:
        val = disease_rows[col].values[0]
        if pd.notna(val) and str(val).strip() not in ['', '0', 'nan']:
            symptom = str(val).strip().replace('_', ' ')
            if symptom:
                symptoms.append(symptom)
    
    return ', '.join(set(symptoms))

def get_precautions_for_disease(disease, precautions_df):
    """Extract precautions for a disease"""
    rows = precautions_df[precautions_df['Disease'] == disease]
    if rows.empty:
        return ""
    
    precautions = []
    for col in ['Precaution_1', 'Precaution_2', 'Precaution_3', 'Precaution_4']:
        if col in rows.columns:
            val = rows[col].values[0]
            if pd.notna(val) and str(val).strip():
                precautions.append(str(val).strip())
    
    return ', '.join(precautions)

def create_combined_text(disease, symptoms, description, precautions):
    """Create rich text for embedding — more context = better search"""
    return f"""
    Disease: {disease}
    Symptoms: {symptoms}
    Description: {description}
    Precautions: {precautions}
    """

def get_ollama_embedding(text):
    """Get embedding from Ollama nomic-embed-text — 768 dimensions"""
    try:
        response = requests.post(
            'http://localhost:11434/api/embeddings',
            json={"model": "nomic-embed-text", "prompt": text},
            timeout=30
        )
        return response.json()['embedding']
    except Exception as e:
        print(f"Embedding error: {e}")
        raise

def main():
    print("Starting disease embedding process...")
    
    # Load datasets
    symptoms_df, descriptions_df, precautions_df, severity_df = load_datasets()
    
    # Load embedding model
    print("Using Ollama nomic-embed-text for embeddings...")
    
    # Connect to database
    conn = psycopg2.connect(**DB_CONFIG)
    cur = conn.cursor()
    
    # Setup database
    setup_database(cur)
    conn.commit()
    
    # Process each disease
    diseases = descriptions_df['Disease'].unique()
    print(f"\nProcessing {len(diseases)} diseases...")
    
    success_count = 0
    error_count = 0
    
    for i, disease in enumerate(diseases):
        try:
            # Get description
            desc_row = descriptions_df[descriptions_df['Disease'] == disease]
            description = desc_row['Description'].values[0] if not desc_row.empty else ""
            
            # Get symptoms
            symptoms = get_symptoms_for_disease(disease, symptoms_df)
            
            # Get precautions
            precautions = get_precautions_for_disease(disease, precautions_df)
            
            # Create combined text
            combined_text = create_combined_text(
                disease, symptoms, description, precautions)
            
            # Generate embedding
            embedding_list = get_ollama_embedding(combined_text)
            
            # Insert into database
            cur.execute("""
                INSERT INTO diseases 
                (name, symptoms, description, precautions, 
                 combined_text, embedding)
                VALUES (%s, %s, %s, %s, %s, %s)
            """, (
                disease,
                symptoms,
                description,
                precautions,
                combined_text,
                json.dumps(embedding_list)
            ))
            
            success_count += 1
            print(f"  [{i+1}/{len(diseases)}] ✓ {disease}")
            
        except Exception as e:
            error_count += 1
            print(f"  [{i+1}/{len(diseases)}] ✗ {disease}: {e}")
    
    conn.commit()
    
    # Also add some India-specific diseases not in Kaggle dataset
    india_specific_diseases = [
        {
            "disease": "Dengue Fever",
            "symptoms": "high fever, severe headache, pain behind eyes, joint and muscle pain, rash, mild bleeding from nose or gums, fatigue, nausea",
            "description": "Dengue is a mosquito-borne viral infection common in India, especially during monsoon season. Caused by Aedes aegypti mosquito bite.",
            "precautions": "use mosquito repellent, wear full sleeve clothes, use mosquito nets, eliminate standing water, seek immediate medical care if platelet count drops"
        },
        {
            "disease": "Typhoid Fever",
            "symptoms": "sustained high fever, weakness, stomach pain, headache, loss of appetite, sometimes rash, constipation or diarrhea",
            "description": "Typhoid is caused by Salmonella typhi bacteria, spread through contaminated food and water. Very common in India.",
            "precautions": "drink boiled or purified water, avoid street food, wash hands regularly, get typhoid vaccine, complete antibiotic course"
        },
        {
            "disease": "Chikungunya",
            "symptoms": "sudden fever, severe joint pain especially in hands and feet, muscle pain, headache, nausea, fatigue, rash",
            "description": "Chikungunya is a viral disease transmitted by Aedes mosquitoes. Joint pain can persist for months. Common in India during monsoon.",
            "precautions": "avoid mosquito bites, use repellent, rest, take pain relievers for joint pain, stay hydrated, no specific antiviral treatment"
        },
        {
            "disease": "Leptospirosis",
            "symptoms": "high fever, severe headache, chills, muscle aches, vomiting, jaundice, red eyes, abdominal pain, diarrhea, rash",
            "description": "Bacterial infection spread through contact with water or soil contaminated with infected animal urine. Common after flooding in India.",
            "precautions": "avoid walking in floodwater barefoot, wear rubber boots, take prophylactic doxycycline if exposed, seek early treatment"
        },
        {
            "disease": "Japanese Encephalitis",
            "symptoms": "fever, headache, vomiting, confusion, difficulty speaking, seizures, stiff neck, sensitivity to light",
            "description": "Viral brain infection spread by Culex mosquitoes. Common in rural India especially in rice-growing areas near pigs.",
            "precautions": "get JE vaccination, use mosquito nets, avoid mosquito bites in endemic areas, no specific treatment available"
        },
        {
            "disease": "Kala Azar (Visceral Leishmaniasis)",
            "symptoms": "prolonged fever weeks to months, weight loss, weakness, swollen spleen and liver, anemia, darkening of skin",
            "description": "Parasitic disease spread by sandfly bites. Common in Bihar, Jharkhand, West Bengal and Uttar Pradesh in India.",
            "precautions": "use insecticide-treated bed nets, indoor residual spraying, avoid sandfly bites, seek early treatment, free treatment available under national program"
        }
    ]
    
    # Deduplicate: skip India-specific diseases already present in Kaggle dataset
    kaggle_diseases = set(descriptions_df['Disease'].str.lower())
    print("\nAdding India-specific diseases (skipping duplicates)...")
    india_added = 0
    for disease_data in india_specific_diseases:
        if disease_data["disease"].lower() in kaggle_diseases:
            print(f"  ⟳ Skipped (already in Kaggle): {disease_data['disease']}")
            continue
        combined_text = create_combined_text(
            disease_data["disease"],
            disease_data["symptoms"],
            disease_data["description"],
            disease_data["precautions"]
        )
        embedding = get_ollama_embedding(combined_text)
        
        cur.execute("""
            INSERT INTO diseases 
            (name, symptoms, description, precautions, 
             combined_text, embedding)
            VALUES (%s, %s, %s, %s, %s, %s)
        """, (
            disease_data["disease"],
            disease_data["symptoms"],
            disease_data["description"],
            disease_data["precautions"],
            combined_text,
            json.dumps(embedding)
        ))
        india_added += 1
        print(f"  ✓ {disease_data['disease']}")
    
    conn.commit()
    cur.close()
    conn.close()
    
    print(f"\n{'='*50}")
    print(f"Complete! {success_count} diseases loaded successfully")
    print(f"India-specific diseases added: {india_added} (of {len(india_specific_diseases)})")
    if error_count:
        print(f"Errors: {error_count}")
    print(f"Total in database: {success_count + india_added}")
    print(f"{'='*50}")
    print("\nYou can now start the Spring Boot app with Ollama RAG!")

if __name__ == "__main__":
    main()
