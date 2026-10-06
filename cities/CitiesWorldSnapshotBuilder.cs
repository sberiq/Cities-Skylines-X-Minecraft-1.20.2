using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;
using ColossalFramework;
using UnityEngine;

namespace CitiesCraft
{
    /// <summary>Builds a bounded terrain and building-collision snapshot around the mapped MC player.</summary>
    internal static class CitiesWorldSnapshotBuilder
    {
        private const float GridSpacing = 8f;
        private const int MaximumBuildings = 128;
        private static readonly ushort[] SegmentCandidates = new ushort[NetManager.MAX_SEGMENT_COUNT];

        public static string Build(long sequence, float centerX, float centerZ, float collisionRadius)
        {
            int gridSize = Mathf.Clamp(Mathf.CeilToInt(collisionRadius / GridSpacing) * 2 + 1, 3, 65);
            int gridPointCount = gridSize * gridSize;
            float half = (gridSize - 1) * GridSpacing * 0.5f;
            float startX = centerX - half;
            float startZ = centerZ - half;
            float[] heights = new float[gridPointCount];
            for (int row = 0; row < gridSize; row++)
            {
                float z = startZ + row * GridSpacing;
                for (int column = 0; column < gridSize; column++)
                {
                    float x = startX + column * GridSpacing;
                    float terrainHeight = TerrainManager.instance.SampleFinalHeightSmooth(x, z);
                    heights[row * gridSize + column] = SampleRoadHeight(x, z, terrainHeight);
                }
            }

            List<BuildingObstacle> buildings = FindBuildings(centerX, centerZ, collisionRadius);
            buildings.Sort(delegate(BuildingObstacle left, BuildingObstacle right)
            {
                return left.DistanceSquared.CompareTo(right.DistanceSquared);
            });
            if (buildings.Count > MaximumBuildings) buildings.RemoveRange(MaximumBuildings, buildings.Count - MaximumBuildings);

            StringBuilder message = new StringBuilder(12000);
            message.Append("SNAPSHOT\t").Append(sequence.ToString(CultureInfo.InvariantCulture));
            Append(message, centerX);
            Append(message, centerZ);
            Append(message, GridSpacing);
            message.Append('\t').Append(gridSize.ToString(CultureInfo.InvariantCulture));
            message.Append('\t').Append(gridSize.ToString(CultureInfo.InvariantCulture));
            for (int i = 0; i < heights.Length; i++) Append(message, heights[i]);
            message.Append('\t').Append(buildings.Count.ToString(CultureInfo.InvariantCulture));
            for (int i = 0; i < buildings.Count; i++)
            {
                BuildingObstacle item = buildings[i];
                message.Append('\t').Append(item.Id.ToString(CultureInfo.InvariantCulture)).Append(',')
                    .Append(item.MinX.ToString("R", CultureInfo.InvariantCulture)).Append(',')
                    .Append(item.MinY.ToString("R", CultureInfo.InvariantCulture)).Append(',')
                    .Append(item.MinZ.ToString("R", CultureInfo.InvariantCulture)).Append(',')
                    .Append(item.MaxX.ToString("R", CultureInfo.InvariantCulture)).Append(',')
                    .Append(item.MaxY.ToString("R", CultureInfo.InvariantCulture)).Append(',')
                    .Append(item.MaxZ.ToString("R", CultureInfo.InvariantCulture));
            }
            return message.ToString();
        }

        private static float SampleRoadHeight(float x, float z, float terrainHeight)
        {
            NetManager manager = NetManager.instance;
            if (manager == null) return terrainHeight;
            Vector3 query = new Vector3(x, terrainHeight, z);
            int count;
            manager.GetClosestSegments(query, SegmentCandidates, out count);
            float bestDistanceSquared = float.MaxValue;
            float bestHeight = terrainHeight;
            float bestHalfWidth = 0f;
            for (int i = 0; i < count; i++)
            {
                ushort id = SegmentCandidates[i];
                if (id == 0) continue;
                NetSegment segment = manager.m_segments.m_buffer[id];
                if ((segment.m_flags & NetSegment.Flags.Created) == NetSegment.Flags.None
                    || segment.Info == null) continue;

                Vector3 point = segment.GetClosestPosition(query);
                float dx = point.x - x;
                float dz = point.z - z;
                float distanceSquared = dx * dx + dz * dz;
                if (distanceSquared < bestDistanceSquared)
                {
                    bestDistanceSquared = distanceSquared;
                    bestHeight = point.y;
                    bestHalfWidth = Mathf.Max(2f, segment.Info.m_halfWidth);
                }
            }

            // A road below the terrain sample is likely a tunnel; leave its terrain roof walkable.
            return bestDistanceSquared <= bestHalfWidth * bestHalfWidth && bestHeight >= terrainHeight - 1f
                ? bestHeight : terrainHeight;
        }

        private static List<BuildingObstacle> FindBuildings(float centerX, float centerZ, float collisionRadius)
        {
            List<BuildingObstacle> result = new List<BuildingObstacle>();
            BuildingManager manager = BuildingManager.instance;
            if (manager == null || manager.m_buildings == null || manager.m_buildings.m_buffer == null) return result;

            Building[] buffer = manager.m_buildings.m_buffer;
            float searchRadius = collisionRadius + 128f;
            float searchRadiusSquared = searchRadius * searchRadius;
            for (int index = 1; index < buffer.Length; index++)
            {
                Building building = buffer[index];
                if ((building.m_flags & Building.Flags.Created) == Building.Flags.None || building.Info == null) continue;
                float dx = building.m_position.x - centerX;
                float dz = building.m_position.z - centerZ;
                float distanceSquared = dx * dx + dz * dz;
                if (distanceSquared > searchRadiusSquared) continue;

                Vector3 position;
                Quaternion rotation;
                Vector3 size;
                building.GetTotalPosition(out position, out rotation, out size);
                BuildingInfo info = building.Info;
                Vector3 center = position + rotation * info.m_centerOffset;
                float width = Mathf.Max(Mathf.Abs(size.x), building.m_width * 8f);
                float length = Mathf.Max(Mathf.Abs(size.z), building.m_length * 8f);
                float height = Mathf.Max(Mathf.Abs(size.y), info.m_collisionHeight);
                if (width < 2f) width = 8f;
                if (length < 2f) length = 8f;
                if (height < 3f) height = 6f;

                float minX = float.MaxValue, minY = float.MaxValue, minZ = float.MaxValue;
                float maxX = float.MinValue, maxY = float.MinValue, maxZ = float.MinValue;
                for (int cx = 0; cx < 2; cx++)
                {
                    for (int cy = 0; cy < 2; cy++)
                    {
                        for (int cz = 0; cz < 2; cz++)
                        {
                            Vector3 local = new Vector3(cx == 0 ? -width * 0.5f : width * 0.5f,
                                cy == 0 ? 0f : height,
                                cz == 0 ? -length * 0.5f : length * 0.5f);
                            Vector3 corner = center + rotation * local;
                            minX = Mathf.Min(minX, corner.x); minY = Mathf.Min(minY, corner.y); minZ = Mathf.Min(minZ, corner.z);
                            maxX = Mathf.Max(maxX, corner.x); maxY = Mathf.Max(maxY, corner.y); maxZ = Mathf.Max(maxZ, corner.z);
                        }
                    }
                }
                if (maxX < centerX - collisionRadius || minX > centerX + collisionRadius
                    || maxZ < centerZ - collisionRadius || minZ > centerZ + collisionRadius) continue;

                long stableId = ((long)building.m_buildIndex << 16) | (uint)index;
                result.Add(new BuildingObstacle(stableId, distanceSquared, minX, minY, minZ, maxX, maxY, maxZ));
            }
            return result;
        }

        private static void Append(StringBuilder builder, float value)
        {
            builder.Append('\t').Append(value.ToString("R", CultureInfo.InvariantCulture));
        }

        private sealed class BuildingObstacle
        {
            public readonly long Id;
            public readonly float DistanceSquared;
            public readonly float MinX, MinY, MinZ, MaxX, MaxY, MaxZ;
            public BuildingObstacle(long id, float distanceSquared,
                float minX, float minY, float minZ, float maxX, float maxY, float maxZ)
            {
                Id = id; DistanceSquared = distanceSquared;
                MinX = minX; MinY = minY; MinZ = minZ; MaxX = maxX; MaxY = maxY; MaxZ = maxZ;
            }
        }
    }
}
