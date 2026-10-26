"""Deterministic classification shared by Home and OS notices. No AI calls."""
import re


def classify(subject, body):
    text = subject + "\n" + body
    # Do not infer the outcome; the original source remains the evidence.
    result_pattern = r"(?:選考|面接|書類審査|適性検査).{0,12}(?:結果|合否)|内定.{0,8}(?:通知|ご案内)"
    # A future-result notice in a generic announcement is not a delivered result.
    future_pattern = r"後日|後ほど|追って|予定|お待ち"
    body_sentences = re.split(r"[\n。！？]", body)
    future_result = any(re.search(result_pattern, sentence) and re.search(future_pattern, sentence) for sentence in body_sentences)
    sentences = body_sentences + ([] if future_result else [subject])
    if any(re.search(result_pattern, sentence) and not re.search(future_pattern, sentence) for sentence in sentences):
        return "result", "選考結果の案内"
    if re.search(r"(?:締切|期限).{0,12}(?:変更|延長|短縮)", text):
        return "action", "期限変更の案内"
    if re.search(r"(?:面接|面談).{0,35}(?:予約|日程選択|日程調整).{0,35}(?:ください|お願い|受付|開始|必要)", text):
        return "action", "面接・面談の予約案内"
    if re.search(r"(?:書類|エントリーシート|ES|回答|課題).{0,30}(?:提出|回答).{0,25}(?:ください|お願い|必要|期限)", text):
        return "action", "提出・回答の依頼"
    return None

