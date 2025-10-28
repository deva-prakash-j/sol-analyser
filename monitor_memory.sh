#!/bin/bash

echo "Memory Monitoring for SOL Application (Port 8090)"
echo "=================================================="
echo "Time | Heap Used (MB) | Usage % | GC Reclaimed (MB) | Total Runtime (MB)"
echo "----------------------------------------------------------------------"

for i in {1..10}; do
    timestamp=$(date "+%H:%M:%S")
    
    # Get memory stats
    memory_data=$(curl -s http://localhost:8090/api/admin/proxy/memory)
    
    if [ $? -eq 0 ]; then
        heap_used=$(echo $memory_data | grep -o '"heap_used_MB":[0-9]*' | cut -d':' -f2)
        heap_percent=$(echo $memory_data | grep -o '"heap_usage_percent":[0-9.]*' | cut -d':' -f2)
        gc_reclaimed=$(echo $memory_data | grep -o '"gcReclaimed_MB":[0-9]*' | cut -d':' -f2)
        runtime_used=$(echo $memory_data | grep -o '"runtime_usedMemory_MB":[0-9]*' | cut -d':' -f2)
        
        printf "%s | %14s | %7s | %17s | %18s\n" "$timestamp" "$heap_used" "$heap_percent" "$gc_reclaimed" "$runtime_used"
    else
        echo "$timestamp | ERROR: Could not connect to application"
    fi
    
    sleep 30
done

echo "Monitoring complete."