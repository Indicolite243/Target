from apps.utils.db import get_mongodb_db
import json

def inspect_snapshots():
    db = get_mongodb_db()
    if db is None:
        print("Failed to connect to MongoDB")
        return

    count = db.account_snapshots.count_documents({})
    print(f"Total snapshots: {count}")

    if count > 0:
        # Get one from 2025
        s2025 = db.account_snapshots.find_one({"date": {"$regex": "^2025"}})
        print(f"Sample 2025 snapshot: {s2025}")

        # Get one from 2026
        s2026 = db.account_snapshots.find_one({"date": {"$regex": "^2026"}})
        print(f"Sample 2026 snapshot: {s2026}")
        
        # Get all distinct years
        pipeline = [
            {"$project": {"year": {"$substr": ["$date", 0, 4]}}},
            {"$group": {"_id": "$year"}}
        ]
        years = list(db.account_snapshots.aggregate(pipeline))
        print(f"Available years: {[y['_id'] for y in years]}")

if __name__ == "__main__":
    inspect_snapshots()
