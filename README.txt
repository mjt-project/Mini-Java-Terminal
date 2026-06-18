MJT 3.0.0-SNAPSHOT+8 - CommandCenter Modular Refactor

Copy the src/ folder over the current MJT core source tree.

What changed:
- CommandCenter.java is now a tiny facade.
- Old command logic moved to CommandDispatcher.java for compatibility.
- Added modular command foundation classes for future cleanup.

Public command syntax stays the same.

Build:
  mvn -U clean package

Test:
  .mjt --version
  .mjt help
  .mjt panel show
  .mjt minecraft installer show
  .mjt minecraft profile list
