from flask import Flask, jsonify
import requests
from bs4 import BeautifulSoup
from urllib.parse import urljoin
from parser import extract_idsp_data
from summarizer import summarize_comment
from collections import defaultdict
import re

app = Flask(__name__)

BASE_URL = "https://idsp.mohfw.gov.in/index4.php?lang=1&level=0&linkid=406&lid=3689"

# ✅ Extract week number (1st → 1, 8th → 8)
def extract_week_number(text):
    match = re.search(r'(\d+)(st|nd|rd|th)', text.lower())
    return int(match.group(1)) if match else 0


def get_latest_pdf_url():
    session = requests.Session()

    try:
        response = session.get(BASE_URL, timeout=10)
        soup = BeautifulSoup(response.text, "html.parser")

        # Step 1: Find ALL links inside table
        rows = soup.find_all("tr")

        latest_week_link = None
        max_week = 0

        for row in rows:
            cols = row.find_all("td")

            if len(cols) < 2:
                continue

            year_text = cols[0].get_text(strip=True)

            # ✅ Focus only on 2026 row
            if "2026" not in year_text:
                continue

            # Step 2: Find all week links in that row
            links = cols[1].find_all("a")

            for link in links:
                text = link.text.strip()

                week_num = extract_week_number(text)

                if week_num > max_week:
                    max_week = week_num
                    latest_week_link = urljoin(BASE_URL, link.get("href"))

        if not latest_week_link:
            return None

        print("Latest week:", max_week)

        # Step 3: Open that week page
        week_response = session.get(latest_week_link, timeout=10)

# 🔥 FIX: if it's already a PDF → return directly
        content_type = week_response.headers.get("Content-Type", "")
        if "application/pdf" in content_type or latest_week_link.lower().endswith(".pdf"):
            print("Direct PDF found")
            return latest_week_link
        week_soup = BeautifulSoup(week_response.text, "html.parser")
        # Step 4: Extract PDF link
        for a in week_soup.find_all("a"):
            href = a.get("href")

            if href and ".pdf" in href.lower():
                return urljoin(BASE_URL, href)

    except Exception as e:
        print("Scraping error:", e)

    return None


def download_pdf(pdf_url):
    try:
        response = requests.get(pdf_url, timeout=30)
        if response.status_code == 200:
            with open("report.pdf", "wb") as f:
                f.write(response.content)
            return "report.pdf"
    except Exception as e:
        print(f"Error downloading PDF: {e}")

    return None


@app.route("/idsp")
def get_data():
    pdf_url = get_latest_pdf_url()

    if not pdf_url:
        return jsonify({"error": "Latest IDSP PDF not found"}), 404

    print("PDF URL:", pdf_url)

    pdf_path = download_pdf(pdf_url)

    if not pdf_path:
        return jsonify({"error": "Download failed"}), 500

    data = extract_idsp_data(pdf_path)

    grouped = defaultdict(list)

    for item in data:
        summary = summarize_comment(item["comment"])  # ✅ FIXED

        grouped[item["district"]].append({
            "disease": item["disease"],
            "cases": item["cases"],
            "summary": summary
        })

    return jsonify(grouped)


if __name__ == "__main__":
    app.run(port=5000)