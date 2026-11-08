// Converts .wav files to DPCM with FamiStudio 4.5.3's own code (WaveFile.Load, Project.CreateDPCMSampleFromWavData,
// DPCMSample.Process), the reference Dpcm.fromWav must match byte for byte. Run by tools/audio/dpcm_reference.py.
//
//   dotnet run -- <FamiStudio.dll dir> <out dir> <file.wav>:<rate>[:<dmcInitialDiv2>] ...
//
// Each conversion writes <out dir>/<file stem>_r<rate>.dmc. Like FamiStudio's sample import, at most two seconds of the
// file are used; padding is "pad to 16 bytes + 1" (the mode Thor Tracker uses).
using System;
using System.IO;
using FamiStudio;

static class Program
{
    static int Main(string[] args)
    {
        var outDir = args[1];
        foreach (var arg in args[2..])
        {
            var parts = arg.Split(':');
            var file = parts[0];
            var rate = int.Parse(parts[1]);
            var initial = parts.Length > 2 ? int.Parse(parts[2]) : 32;
            var wav = WaveFile.Load(file, out var sampleRate);
            if (wav == null) { Console.Error.WriteLine($"{file}: FamiStudio could not load it"); return 1; }
            if (wav.Length > sampleRate * 2) Array.Resize(ref wav, sampleRate * 2); // ProjectExplorer's import limit
            var project = new Project();
            var sample = project.CreateDPCMSampleFromWavData(Path.GetFileNameWithoutExtension(file), wav, sampleRate, file);
            sample.SampleRate = rate;
            sample.DmcInitialValueDiv2 = initial;
            sample.PaddingMode = DPCMPaddingType.PadTo16BytesPlusOne;
            sample.Process();
            var name = $"{Path.GetFileNameWithoutExtension(file)}_r{rate}" + (initial != 32 ? $"_i{initial}" : "") + ".dmc";
            File.WriteAllBytes(Path.Combine(outDir, name), sample.ProcessedData);
            Console.WriteLine($"{name}: {sample.ProcessedData.Length} bytes");
        }
        return 0;
    }
}
