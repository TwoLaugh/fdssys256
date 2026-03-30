# Module: Health

## Purpose
Tracks health signals over time (mood, energy, symptoms, weight, progress photos). Generates AI-powered weekly and monthly reviews that correlate food with health outcomes. Later tiers: wearable sync, blood panels, genomics.

## Dependencies
- **→ NutritionTracker.getWeeklyNutrition()** — what was eaten
- **→ Planner.getCurrentPlan()** — planned meals for correlation
- **→ Pantry.getWasteLog()** — waste stats for reviews
- **→ Shopping (via Planner)** — budget stats
- **→ Feedback.getRecentFeedback()** — meal satisfaction data
- **→ Profile.getNutritionTargets()** — goals for review context
- **→ AI.execute(WEEKLY_REVIEW)** — generate review
- **→ AI.execute(MONTHLY_REVIEW)** — generate monthly review

## Data Model

### health_log
```sql
CREATE TABLE health_log (
    id              BIGSERIAL PRIMARY KEY,
    date            DATE NOT NULL,
    time_of_day     VARCHAR(20),              -- morning/midday/evening or null
    mood_score      INTEGER CHECK (mood_score BETWEEN 1 AND 5),
    energy_score    INTEGER CHECK (energy_score BETWEEN 1 AND 5),
    sleep_quality   INTEGER CHECK (sleep_quality BETWEEN 1 AND 5),
    weight_kg       DECIMAL(5,2),
    symptoms        VARCHAR(50)[],            -- {bloating,headache,brain_fog,fatigue}
    notes           TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_hl_date ON health_log(date DESC);
```

### progress_photo
```sql
CREATE TABLE progress_photo (
    id          BIGSERIAL PRIMARY KEY,
    date        DATE NOT NULL,
    file_path   TEXT NOT NULL,
    notes       TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### health_review
Cached AI-generated reviews.

```sql
CREATE TABLE health_review (
    id              BIGSERIAL PRIMARY KEY,
    review_type     VARCHAR(20) NOT NULL,     -- weekly/monthly
    period_start    DATE NOT NULL,
    period_end      DATE NOT NULL,
    content         JSONB NOT NULL,           -- structured review data
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(review_type, period_start)
);
```

## API

### POST /api/v1/health/log
Log a health check-in.

**Request:**
```json
{
  "date": "2026-03-23",
  "timeOfDay": "evening",
  "moodScore": 4,
  "energyScore": 3,
  "sleepQuality": 4,
  "weightKg": 75.5,
  "symptoms": ["mild_bloating"],
  "notes": "Felt bloated after lunch"
}
```

**Response 201:** stored entry.

### GET /api/v1/health/log?from={date}&to={date}
Health logs for a date range.

### GET /api/v1/health/weight?from={date}&to={date}
Weight history for charting.

**Response 200:**
```json
{
  "entries": [
    {"date": "2026-03-20", "weightKg": 76.0},
    {"date": "2026-03-23", "weightKg": 75.5}
  ],
  "trend": "slight_decrease",
  "averageKg": 75.75
}
```

### GET /api/v1/health/review/weekly/{weekStartDate}
Get or generate weekly review.

**Flow (if not cached):**
1. → NutritionTracker.getWeeklyNutrition(week) → what was eaten
2. → Health logs for the week → mood, energy, symptoms, weight
3. → Feedback.getRecentFeedback() → meal satisfaction
4. → Pantry.getWasteLog(week) → waste stats
5. → Profile.getNutritionTargets() → goals
6. → AI.execute(WEEKLY_REVIEW, {all above context})
7. Cache the review

**Response 200:**
```json
{
  "weekStartDate": "2026-03-23",
  "summary": "Good week overall. Protein consistently hit target...",
  "nutrition": {
    "avgCalories": 2050,
    "avgProteinG": 148,
    "calorieTargetHitDays": 5,
    "proteinTargetHitDays": 6
  },
  "health": {
    "avgMood": 3.8,
    "avgEnergy": 3.5,
    "weightChange": -0.5,
    "symptomsSummary": "Bloating reported twice, both after meals with chickpeas"
  },
  "patterns": [
    "Bloating correlates with chickpea-heavy meals (3 of 4 occasions)",
    "Energy was highest on days with 40g+ protein at lunch"
  ],
  "suggestions": [
    "Consider reducing chickpea frequency or trying longer soaking",
    "Front-load protein to lunch — your energy responds well to it"
  ],
  "waste": {
    "totalPence": 250,
    "items": 2
  }
}
```

### GET /api/v1/health/review/monthly/{YYYY-MM}
Monthly review (same pattern, broader scope).

### POST /api/v1/health/photos
Upload a progress photo.

### GET /api/v1/health/photos
Photo timeline.

## Service Interface

```java
public interface HealthService {
    HealthLogDto logCheckIn(CreateHealthLogRequest request);
    List<HealthLogDto> getLogs(LocalDate from, LocalDate to);
    WeightHistoryDto getWeightHistory(LocalDate from, LocalDate to);

    WeeklyReviewDto getWeeklyReview(LocalDate weekStartDate);
    MonthlyReviewDto getMonthlyReview(YearMonth month);

    ProgressPhotoDto uploadPhoto(LocalDate date, String filePath, String notes);
    List<ProgressPhotoDto> getPhotos();
}
```

## Consumed By
- **Profile** — reviews may suggest updating nutrition targets or adding intolerances
- **Planner** — symptom patterns influence future planning (via preference model updates)

## Events Emitted
- `health.symptom_pattern_detected` — recurring symptom correlated with food
- `health.review_ready` — weekly/monthly review generated
