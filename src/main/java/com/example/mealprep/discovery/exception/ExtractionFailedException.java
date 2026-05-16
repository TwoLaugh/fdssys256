package com.example.mealprep.discovery.exception;

/**
 * Inability of a {@code DiscoverySource} to produce a coherent {@code ParsedRecipe} from a fetched
 * page. Caught by the runner (01d) and converted to an {@code EXTRACTION_FAILED} scrape row — NOT
 * mapped in {@code DiscoveryExceptionHandler} because this exception never reaches the controller
 * layer.
 */
public class ExtractionFailedException extends DiscoveryException {

  private final String candidateUrl;

  public ExtractionFailedException(String candidateUrl, String reason) {
    super("extraction failed for '" + candidateUrl + "': " + reason);
    this.candidateUrl = candidateUrl;
  }

  public String getCandidateUrl() {
    return candidateUrl;
  }
}
