import pdfplumber

def clean_text(text):
    if not text:
        return ""
    return " ".join(str(text).split())


def extract_idsp_data(pdf_path):
    data = []
    current_item = None

    with pdfplumber.open(pdf_path) as pdf:
        for page in pdf.pages:
            tables = page.extract_tables()

            for table in tables:
                for row in table:
                    if not row:
                        continue

                    row = [clean_text(cell) for cell in row]
                    row_text = " ".join(row).lower()

                    # 🔥 Skip headers
                    if "district" in row_text or "s. no." in row_text:
                        continue

                    # 🕵️ Check if this is a "New Report" row (usually starts with a number or has a district)
                    # A new report usually has a district name at index 2
                    is_new_report = len(row) > 3 and len(row[2]) > 3 and row[2][0].isupper()

                    if is_new_report:
                        try:
                            # If we have a previous item, save it
                            if current_item:
                                data.append(current_item)

                            # Start a new report
                            district = row[2]
                            disease  = row[3]
                            cases    = row[4] if len(row) > 4 else ""
                            
                            # Identify the comment (usually the longest cell or the last cell)
                            comment = row[-1] if len(row) > 5 else ""
                            longest_cell = max(row, key=len)
                            if len(longest_cell) > len(comment) + 20:
                                comment = longest_cell

                            current_item = {
                                "district": district,
                                "disease": disease,
                                "cases": cases,
                                "comment": comment
                            }
                        except Exception as e:
                            print(f"Row parsing error: {e}")
                            continue
                    
                    # 🧩 Overflow Handling: If this row is NOT a new report but has text, 
                    # it's likely a continuation of the previous 'Action Taken' comment.
                    elif current_item and len(row_text) > 10:
                        longest_cell = max(row, key=len)
                        if len(longest_cell) > 5:
                            current_item["comment"] += " " + longest_cell

        # Don't forget the last item
        if current_item:
            data.append(current_item)

    return data