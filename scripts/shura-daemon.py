"""Long-running scheduler entry point; stop with SIGINT/SIGTERM."""
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from shura_core.daemon import main
if __name__ == "__main__": main()
