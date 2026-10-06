using ICities;

namespace CitiesCraft
{
    public sealed class CitiesCraftMod : IUserMod
    {
        public string Name { get { return "CitiesCraft Passthrough"; } }
        public string Description { get { return "Local telemetry link for Minecraft Java 1.20.2."; } }
    }
}
