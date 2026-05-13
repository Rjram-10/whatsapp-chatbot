import requests
import json

# Local Ollama endpoint
OLLAMA_URL = "http://localhost:11434/api/generate"
MODEL = "llama3.2"

def summarize_comment(comment):
    if not comment or len(comment) < 10:
        return "Monitoring the situation."

    # Clean extra spaces
    comment = " ".join(comment.split())

    try:
        # Using the 'system' parameter to force Ollama into extraction mode
        payload = {
            "model": MODEL,
            "prompt": "Report: " + comment,
            "system": (
                "You are a medical data extraction tool. Your ONLY job is to summarize the 'Action Taken' "
                "from the report into ONE short, active sentence (max 15 words) for WhatsApp. "
                "Do NOT include any introductory text, pleasantries, or phrases like 'I'm happy to help'. "
                "Just output the summary text itself."
            ),
            "stream": False,
            "options": {
                "temperature": 0.1, # Extremely low temperature for deterministic output
                "num_predict": 50    # Limit output length at the engine level
            }
        }
        
        response = requests.post(OLLAMA_URL, json=payload, timeout=20)
        result = response.json()
        
        summary = result.get('response', '').strip()
        
        # Final cleanup: remove quotes and common AI conversational prefixes
        summary = summary.strip('"').strip("'").strip()
        
        # Safety check: if Ollama still gives a chatty response, try to pick the last sentence or a fallback
        if "happy to help" in summary.lower() or "provided" in summary.lower() and "report" in summary.lower():
            if len(comment) > 10:
                # Fallback to a simple truncation if the AI fails to be concise
                return comment[:100] + "..." if len(comment) > 100 else comment
            return "Monitoring the situation."
            
        return summary
    except Exception as e:
        print(f"Ollama error: {e}")
        if len(comment) > 100:
            return comment[:97] + "..."
        return comment