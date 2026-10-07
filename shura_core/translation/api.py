from dataclasses import dataclass
from enum import StrEnum
class TranslationMode(StrEnum): FULL="full"; FOLLOW_READING="follow-reading"
@dataclass(frozen=True)
class TranslationRequest:
    chapter_id:str
    page:int
    source_language:str
    target_language:str
    mode:TranslationMode
class TranslationEngine:
    """Future engine boundary. Implementations return page text/assets; no overlays or touch interception."""
    def translate_page(self,request:TranslationRequest):raise NotImplementedError("translation engine is not implemented")
