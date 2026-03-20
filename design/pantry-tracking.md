# Pantry Tracking — Automated Approaches

## Manual Baseline (v1)
- User adds items when they buy them (or auto-added from shopping list)
- User removes/adjusts when they cook
- Simple but relies on discipline

## Automated Ideas (future)

### Receipt Scanning
- Phone camera → OCR → parse items + quantities
- Works for: supermarket shops (structured receipts)
- Challenges: OCR accuracy, mapping "TESCO CHICKEN BRST 500G" to a pantry item, handling multi-buy deals
- Tech: phone camera + vision model (Claude vision or lightweight OCR)
- **Effort: Medium. Probably the best bang-for-buck automation.**

### Tesco Order History Sync
- If we're already integrating with Tesco (phase 3), we could pull order history
- Exact items + quantities already structured
- Only covers Tesco shops though — not corner shop trips, farmers markets etc.
- **Effort: Low (if Tesco integration already exists). Very reliable.**

### Smart Scales / IoT
- Weight-based tracking on shelves
- Unrealistic for a personal project, but exists commercially (e.g., Amazon Dash)
- **Effort: Very high. Not practical.**

### Deduction from Cooking
- When user marks a meal as "cooked", auto-deduct the recipe's ingredients from pantry
- This is easy and should be in v1 honestly
- Edge case: user used different quantities, substituted ingredients
- Could prompt: "You cooked Chicken Stir Fry — I've removed these from your pantry: [list]. Anything different?"
- **Effort: Low. Should do this early.**

### Barcode Scanning
- Scan items as they come into the kitchen
- Maps to product databases (Open Food Facts API is free)
- Gets you: product name, nutrition info, category
- Doesn't get you: expiry date, exact quantity remaining
- **Effort: Medium. Nice for nutrition data enrichment.**

## Recommended Approach
1. **v1**: Auto-deduct when meals are cooked + manual add from shopping list
2. **v2**: Tesco order history sync (comes free with grocery automation)
3. **v3**: Receipt scanning as a nice-to-have
