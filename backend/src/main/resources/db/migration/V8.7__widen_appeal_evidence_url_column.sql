-- Widen evidence_url column in appeal_evidence_urls table to TEXT to allow Base64 encoded image data URLs
ALTER TABLE appeal_evidence_urls ALTER COLUMN evidence_url TYPE TEXT;
