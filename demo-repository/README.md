# ReviewForge demo repository

This is a small Java 25/Maven repository with a correct `main` branch and two patches that create
realistic, deliberately buggy pull requests. The existing tests stay green after either patch;
ReviewForge should identify the missing edge case and can generate a JUnit test that exposes it.

## Create the demo pull requests

Create an empty GitHub repository, then initialize and push this directory as its `main` branch:

```bash
git init
git add .
git commit -m "Create safe order services"
git branch -M main
git remote add origin git@github.com:YOUR_ACCOUNT/reviewforge-demo.git
git push -u origin main
```

Create each scenario from a fresh copy of `main`:

```bash
git switch -c demo/oversell main
git apply scenarios/pr-01-oversell.patch
git add src/main/java/ai/reviewforge/demo/InventoryService.java
git commit -m "Simplify inventory availability check"
git push -u origin demo/oversell

git switch -c demo/shipping-threshold main
git apply scenarios/pr-02-threshold-regression.patch
git add src/main/java/ai/reviewforge/demo/ShippingQuote.java
git commit -m "Clarify free-shipping threshold"
git push -u origin demo/shipping-threshold
```

Open both branches as pull requests into `main`. Expected review targets:

- `demo/oversell`: a quantity larger than the remaining stock is accepted and drives inventory
  negative.
- `demo/shipping-threshold`: an order exactly equal to `50.00` is charged shipping even though
  the threshold is inclusive.

Run the safe baseline with `mvn test`. To prove a scenario locally, apply its patch and add the
missing boundary test; do not commit a generated test to `main` unless you also fix the defect.

