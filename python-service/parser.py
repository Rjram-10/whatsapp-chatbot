import pdfplumber

def clean_text(text):
    if not text:
        return ""
    return " ".join(str(text).split())


def extract_idsp_data(pdf_path):
    data = []

    with pdfplumber.open(pdf_path) as pdf:
        for page in pdf.pages:

            tables = page.extract_tables()

            for table in tables:
                for row in table:

                    if not row:
                        continue

                    row = [clean_text(cell) for cell in row]

                    # 🔥 Skip headers
                    if "district" in " ".join(row).lower():
                        continue

                    try:
                        # 🔥 DYNAMIC DETECTION

                        # Find district (usually proper word)
                        district = row[2] if len(row) > 2 else ""

                        # Find disease (text field)
                        disease = row[3] if len(row) > 3 else ""

                        # Find numeric cases
                        cases = ""
                        for cell in row:
                            if cell.isdigit():
                                cases = cell
                                break

                        # Find status
                        status = ""
                        for cell in row:
                            if "surveillance" in cell.lower() or "control" in cell.lower():
                                status = cell
                                break

                        # 🔥 Comment = LAST COLUMN (IMPORTANT)
                        comment = row[-1] if len(row) > 5 else ""

                        # Skip invalid rows
                        if not district or not disease:
                            continue

                        data.append({
                            "district": district,
                            "disease": disease,
                            "cases": cases,
                            "status": status,
                            "comment": comment
                        })

                    except Exception as e:
                        print("Row error:", e)
                        continue

    return data