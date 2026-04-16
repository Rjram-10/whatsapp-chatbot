def summarize_comment(comment):
    if not comment:
        return ""

    # Clean extra spaces
    comment = " ".join(comment.split())

    # Keep it short for chatbot
    if len(comment) > 120:
        return comment[:120] + "..."

    return comment