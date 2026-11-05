#!/usr/bin/env python3
"""Build GCJ-02 ring polygons from OpenStreetMap road-route relations.

The route relations contain both carriageways. Select the smaller closed loop
for each ring so that points on the road's outer edge are not classified inside.
Source: https://www.openstreetmap.org/copyright (ODbL).
"""

import collections
import json
import math
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

RELATIONS = {"secondRing": 295094, "sixthRing": 295982}
OUTPUT = Path(__file__).resolve().parents[1] / "backend/src/main/resources/static/ring-boundaries.json"


def gcj02(lon, lat):
    x, y = lon - 105.0, lat - 35.0
    def latitude_delta(x, y):
        return (-100 + 2*x + 3*y + .2*y*y + .1*x*y + .2*math.sqrt(abs(x))
                + (20*math.sin(6*x*math.pi) + 20*math.sin(2*x*math.pi))*2/3
                + (20*math.sin(y*math.pi) + 40*math.sin(y/3*math.pi))*2/3
                + (160*math.sin(y/12*math.pi) + 320*math.sin(y*math.pi/30))*2/3)
    def longitude_delta(x, y):
        return (300 + x + 2*y + .1*x*x + .1*x*y + .1*math.sqrt(abs(x))
                + (20*math.sin(6*x*math.pi) + 20*math.sin(2*x*math.pi))*2/3
                + (20*math.sin(x*math.pi) + 40*math.sin(x/3*math.pi))*2/3
                + (150*math.sin(x/12*math.pi) + 300*math.sin(x/30*math.pi))*2/3)
    rad_lat = lat / 180 * math.pi
    magic = 1 - 0.00669342162296594323 * math.sin(rad_lat)**2
    sqrt_magic = math.sqrt(magic)
    return (lon + longitude_delta(x, y)*180/(6378245/sqrt_magic*math.cos(rad_lat)*math.pi),
            lat + latitude_delta(x, y)*180/((6378245*(1-0.00669342162296594323))/(magic*sqrt_magic)*math.pi))


def area(points):
    return abs(sum(a[0]*b[1] - b[0]*a[1] for a, b in zip(points, points[1:] + points[:1])) / 2)


def perpendicular(point, start, end):
    # Metres on a local Beijing tangent plane.
    scale_x = 85000
    scale_y = 111000
    dx, dy = (end[0]-start[0])*scale_x, (end[1]-start[1])*scale_y
    px, py = (point[0]-start[0])*scale_x, (point[1]-start[1])*scale_y
    if dx == dy == 0:
        return math.hypot(px, py)
    t = max(0, min(1, (px*dx+py*dy)/(dx*dx+dy*dy)))
    return math.hypot(px-t*dx, py-t*dy)


def simplify(points, tolerance=12):
    if len(points) < 3:
        return points
    index = max(range(1, len(points)-1), key=lambda i: perpendicular(points[i], points[0], points[-1]))
    if perpendicular(points[index], points[0], points[-1]) <= tolerance:
        return [points[0], points[-1]]
    return simplify(points[:index+1], tolerance)[:-1] + simplify(points[index:], tolerance)


def closed_loops(relation_id):
    url = f"https://www.openstreetmap.org/api/0.6/relation/{relation_id}/full"
    root = ET.fromstring(urllib.request.urlopen(url, timeout=60).read())
    nodes = {n.get("id"): (float(n.get("lon")), float(n.get("lat"))) for n in root.findall("node")}
    ways = {w.get("id"): [n.get("ref") for n in w.findall("nd")] for w in root.findall("way")}
    members = [m.get("ref") for m in root.find("relation").findall("member") if m.get("type") == "way"]
    adjacency = collections.defaultdict(list)
    for member in members:
        a, b = ways[member][0], ways[member][-1]
        adjacency[a].append(member)
        adjacency[b].append(member)
    loops = []
    visited = set()
    for member in members:
        if member in visited:
            continue
        start = ways[member][0]
        current, next_way = start, member
        path = []
        while next_way not in visited:
            visited.add(next_way)
            sequence = ways[next_way] if ways[next_way][0] == current else list(reversed(ways[next_way]))
            path.extend(nodes[n] for n in sequence[:-1])
            current = sequence[-1]
            choices = [w for w in adjacency[current] if w not in visited]
            if not choices:
                break
            next_way = choices[0]
        if current == start and len(path) > 100:
            loops.append(path)
    if not loops:
        raise ValueError(f"No closed ring found in relation {relation_id}")
    return loops


def main():
    result = {"coordinateSystem": "GCJ02", "source": "© OpenStreetMap contributors, ODbL",
              "sourceUrl": "https://www.openstreetmap.org/copyright", "relations": RELATIONS}
    for name, relation_id in RELATIONS.items():
        loop = min(closed_loops(relation_id), key=area)
        # Break the closed loop at two points, then simplify each half.
        halfway = len(loop) // 2
        reduced = simplify(loop[:halfway+1])[:-1] + simplify(loop[halfway:] + [loop[0]])
        result[name] = [[round(lon, 7), round(lat, 7)] for lon, lat in (gcj02(*p) for p in reduced)]
        print(name, len(loop), "->", len(reduced), "points")
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(OUTPUT)


if __name__ == "__main__":
    main()
