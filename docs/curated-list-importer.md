# Curated List Importer CLI

`CuratedListImporter` (`src/main/java/armchair/tool/CuratedListImporter.java`) is a standalone Spring Boot CLI tool for importing curated book lists from JSON files into the database.

**Usage:**
```bash
mvn exec:java -Dexec.mainClass="armchair.tool.CuratedListImporter" -Dexec.args="/absolute/path/to/file.json"
```

**JSON format:**
```json
{
  "username": "List Name",
  "books": [
    {"rank": "1", "title": "Title", "author": "Author", "category": "fiction", "review": ""},
    {"rank": "", "title": "Unranked Book", "author": "Author", "category": "non-fiction", "review": "A review"}
  ]
}
```

**List files** are stored in `src/main/resources/lists/`
