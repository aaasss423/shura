from abc import ABC,abstractmethod
from dataclasses import dataclass,field
from shura_core.models import Candidate,Source
@dataclass
class CrawlResult:
    candidates:list[Candidate]=field(default_factory=list)
    pages:int=0
    trace:list[dict]=field(default_factory=list)
    counters:dict=field(default_factory=lambda:{"knownPages":0,"knownEntries":0,"knownSkipped":0,"duplicatesSkipped":0,"duplicateCandidates":0,"knownEntriesSkipped":0,"knownPagesSkipped":0,"listingLinksSkipped":0,"candidateLimitSkipped":0})
class Crawler(ABC):
    @abstractmethod
    def crawl(self,source:Source,budget:dict|None=None)->CrawlResult:...
