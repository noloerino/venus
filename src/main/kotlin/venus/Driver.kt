package venus

/* ktlint-disable no-wildcard-imports */

import venusbackend.assembler.Assembler
import venusbackend.assembler.AssemblerError
import venusbackend.cli.*
import venus.vfs.VirtualFileSystem
import venusbackend.linker.LinkedProgram
import venusbackend.linker.Linker
import venusbackend.linker.ProgramAndLibraries
import venusbackend.riscv.*
import venusbackend.simulator.*
import venusbackend.simulator.Tracer.Companion.wordAddressed
import venusbackend.simulator.cache.BlockReplacementPolicy
import venusbackend.simulator.cache.CacheError
import venusbackend.simulator.cache.CacheHandler
import venusbackend.simulator.cache.PlacementPolicy
import java.io.File
import java.io.FileNotFoundException
import kotlin.jvm.JvmStatic
import kotlin.system.exitProcess

/* ktlint-enable no-wildcard-imports */

/**
 * The "driver" singleton which can be called from Javascript for all functionality.
 */
object Driver {

    var VFS = VirtualFileSystem("v")

    var simState: SimulatorState = SimulatorState32()
    var sim: Simulator = Simulator(LinkedProgram(), VFS, state = simState)
    var tr: Tracer = Tracer(sim)

    val mainCache: CacheHandler = CacheHandler(1)
    var cache: CacheHandler = mainCache
    var cacheLevels: ArrayList<CacheHandler> = arrayListOf(mainCache)

    val simSettings = SimulatorSettings()
    var p = ""
    private var ready = false
    var FReginputAsFloat = true

    var debug = false

    var lastReadFile: File? = null
    var workingdir = "."

    @JvmStatic
    fun main(args: Array<String>) {
        val cli = CommandLineInterface("venus")
        val assemblyTextFile by cli.positionalArgument("file", "This is the file/filepath you want to assemble", "", minArgs = 1)
        val regWidth by cli.flagValueArgument(listOf("-r", "--regwidth"), "RegisterWidth", "Sets register width (Currently only supporting 32 (default) and 64).", "32")
        val trace by cli.flagArgument(listOf("-t", "--trace"), "Trace the program given with the pattern given. If no pattern is given, it will use the default.", false, true)
        val template by cli.flagValueArgument(listOf("-tf", "--tracetemplate"), "TemplateFile", "Optional file/filepath to trace template to use. Only used if the trace argument is set.")
        val traceBase by cli.flagValueArgument(listOf("-tb", "--tracebase"), "Radix", "The radix which you want the trace to output. Default is 2 if omitted", "2") {
            val radix = try {
                it.toInt()
            } catch (e: Exception) {
                val msg = "Could not parse radix input '$it'"
                throw NumberFormatException(msg)
            }
            if (radix !in Character.MIN_RADIX..Character.MAX_RADIX) {
                val msg = "radix $radix was not in valid range ${Character.MIN_RADIX..Character.MAX_RADIX}"
                throw IllegalArgumentException(msg)
            }
            radix.toString()
        }
        val traceInstFirst by cli.flagArgument(listOf("-ti", "--traceInstFirst"), "Sets the tracer to put instructions first.", false, true)
        val tracePCWordAddr by cli.flagArgument(listOf("-tw", "--tracePCWordAddr"), "Sets the pc output of the trace to be word addressed.", false, true)
        val traceTwoStage by cli.flagArgument(listOf("-ts", "--traceTwoStage"), "Sets the trace to be two stages.", false, true)
        val traceTotalNumCommands by cli.flagValueArgument(listOf("-tn", "--traceTotalNumCommands"), "NumberOfCommands", "Sets the number of trace lines which will be printed (negative is ignored).", -1) { it.toInt() }
        val dumpInsts by cli.flagArgument(listOf("-d", "--dump"), "Dumps the instructions of the input program then quits.", false, true)
        val unsetRegisters by cli.flagArgument(listOf("-ur", "--unsetRegisters"), "All registers start as 0 when set.", false, true)

        val getNumberOfCycles by cli.flagArgument(listOf("-n", "--numberCycles"), "Prints out the total number of cycles.", false, true)

        val simArgs by cli.positionalArgumentsList("simulatorArgs", "Args which are put into the simulated program.")

        try {
            cli.parse(args)
        } catch (e: Exception) {
            exitProcess(-1)
        }

        simState = when (regWidth) {
            "32" -> { SimulatorState32() }
            "64" -> { SimulatorState64() }
            else -> { throw SimulatorError("Invalid register width $regWidth") }
        }

        if (getNumberOfCycles) {
            Renderer.activeDisplay = false
        }

        simSettings.setRegesOnInit = !unsetRegisters

        val progs = ArrayList<Program>()

        val assemblyProgramText = readFileDirectlyAsText(assemblyTextFile)

        val prog = assemble(assemblyProgramText)
        if (prog == null) {
            exitProcess(-1)
        } else {
            progs.add(prog)
        }

        link(progs)

        try {
            for (i in 1 until simArgs.size) {
                sim.addArg(simArgs[i])
            }

            if (trace) {
                if (template != null) {
                    tr.format = readFileDirectlyAsText(template as String)
                }
                tr.base = traceBase.toInt()
                tr.instFirst = traceInstFirst
                wordAddressed = tracePCWordAddr
                tr.twoStage = traceTwoStage
                tr.totCommands = traceTotalNumCommands
                trace()
            } else if (dumpInsts) {
                dump()
            } else {
                sim.run()
                if (getNumberOfCycles) {
                    Renderer.activeDisplay = true
                    Renderer.printConsole(sim.getCycles())
                }
            }
//            println() // This is to end on a new line regardless of the output.
        } catch (e: Exception) {
            println(e)
            exitProcess(-1)
        }
    }

    fun readFileDirectlyAsText(fileName: String): String {
        return try {
            val f = File(fileName)
            lastReadFile = f
            workingdir = f.absoluteFile.parent
            f.readText(Charsets.UTF_8)
        } catch (e: FileNotFoundException) {
            println("Could not find the file: " + fileName)
            exitProcess(1)
        }
    }

    /**
     * Assembles and links the program, sets the venusbackend.simulator
     *
     * @param text the assembly code.
     */
    internal fun assemble(text: String): Program? {
        val (prog, errors, warnings) = Assembler.assemble(text)
        if (warnings.isNotEmpty()) {
            for (warning in warnings) {
                Renderer.displayWarning(warning)
            }
        }
        if (errors.isNotEmpty()) {
            println("Could not assemble program!\nHere are the errors which were produced:")
            for (error in errors) {
                Renderer.displayError(error)
            }
            return null
        }
        return prog
    }

    internal fun link(progs: List<Program>): Boolean {
        try {
            val PandL = ProgramAndLibraries(progs, VFS)
            val linked = Linker.link(PandL)
            loadSim(linked)
            return true
        } catch (e: AssemblerError) {
            Renderer.displayError(e)
            return false
        }
    }

    fun loadSim(linked: LinkedProgram) {
        sim = Simulator(linked, VFS, simSettings, state = simState)
        mainCache.reset()
        sim.state.cache = mainCache
        tr = Tracer(sim)
    }

    internal const val TIMEOUT_CYCLES = 100

    fun destrictiveGetSimOut(): String {
        val tmp = sim.stdout
        sim.stdout = ""
        return tmp
    }

    fun getInstructionDump(): String {
        val sb = StringBuilder()
        for (i in 0 until sim.linkedProgram.prog.insts.size) {
            val mcode = sim.linkedProgram.prog.insts[i]
            val hexRepresentation = Renderer.toHex(mcode[InstructionField.ENTIRE])
            sb.append(hexRepresentation/*.removePrefix("0x")*/)
            sb.append("\n")
        }
        return sb.toString()
    }

    fun dump() {
        try {
            Renderer.printConsole(getInstructionDump())
        } catch (e: Throwable) {
            handleError("dump", e)
        }
    }

    fun setOnlyEcallExit(b: Boolean) {
        simSettings.ecallOnlyExit = b
    }

    fun setSetRegsOnInit(b: Boolean) {
        simSettings.setRegesOnInit = b
    }

    fun setNumberOfCacheLevels(i: Int) {
        if (i == cacheLevels.size) {
            return
        }
        if (cacheLevels.size < i) {
            val lastCache = cacheLevels[cacheLevels.size - 1]
            while (cacheLevels.size < i) {
                val newCache = CacheHandler(cacheLevels.size + 1)
                cacheLevels[cacheLevels.size - 1].nextLevelCacheHandler = newCache
                cacheLevels.add(newCache)
            }
            lastCache.update()
        } else if (cacheLevels.size > i) {
            while (cacheLevels.size > i) {
                val prevCacheIndex = cacheLevels.size - 1
                val prevCache = cacheLevels[prevCacheIndex]
                cacheLevels.removeAt(prevCacheIndex)
                val lastCache = cacheLevels[cacheLevels.size - 1]
                lastCache.nextLevelCacheHandler = null
                if (cache.cacheLevel == prevCache.cacheLevel) {
                    cache = lastCache
                }
            }
        }
    }

    fun setCacheEnabled(enabled: Boolean) {
        cache.attach(enabled)
    }

    fun updateCacheLevel(value: String) {
        try {
            val level = value.removePrefix("L").toInt()
            updateCacheLvl(level)
        } catch (e: NumberFormatException) {
            handleError("Update Cache Level (NFE)", e, true)
        }
    }

    fun updateCacheLvl(level: Int) {
        if (level in 1..cacheLevels.size) {
            cache = cacheLevels[level - 1]
        } else {
            handleError("Update Cache Level (LVL)", CacheError("Cache level '" + level + "' does not exist in your current cache!"), true)
        }
    }

    fun updateCacheBlockSize(value: String) {
        val v = value.toInt()
        try {
            cache.setCacheBlockSize(v)
        } catch (er: CacheError) {
            Renderer.printConsole(er.toString())
        }
    }

    fun updateCacheNumberOfBlocks(value: String) {
        val v = value.toInt()
        try {
            cache.setNumberOfBlocks(v)
        } catch (er: CacheError) {
            Renderer.printConsole(er.toString())
        }
    }

    fun updateCacheAssociativity(value: String) {
        val v = value.toInt()
        try {
            cache.setAssociativity(v)
        } catch (er: CacheError) {
            Renderer.printConsole(er.toString())
        }
    }

    fun updateCachePlacementPolicy(value: String) {
        if (value == "N-Way Set Associative") {
            cache.setPlacementPol(PlacementPolicy.NWAY_SET_ASSOCIATIVE)
        } else if (value == "Fully Associative") {
            cache.setPlacementPol(PlacementPolicy.FULLY_ASSOCIATIVE)
        } else {
            cache.setPlacementPol(PlacementPolicy.DIRECT_MAPPING)
        }
    }

    fun updateCacheReplacementPolicy(value: String) {
        if (value == "Random") {
            cache.setBlockRepPolicy(BlockReplacementPolicy.RANDOM)
        } else {
            cache.setBlockRepPolicy(BlockReplacementPolicy.LRU)
        }
    }

    fun setCacheSeed(v: String) {
        cache.setCurrentSeed(v)
    }

    fun setAlignedAddressing(b: Boolean) {
        simSettings.alignedAddress = b
    }

    fun setMutableText(b: Boolean) {
        simSettings.mutableText = b
    }

    fun trace() {
        traceSt()
    }

    internal fun traceSt() {
        try {
            tr.traceStart()
            traceLoop()
        } catch (e: Throwable) {
            handleError("Trace tr Start", e, e is AlignmentError || e is StoreError)
        }
    }

    internal fun traceLoop() {
        try {
            var cycles = 0
            while (cycles < TIMEOUT_CYCLES) {
                if (sim.isDone()) {
                    runTrEnd()
                    return
                }
                tr.traceStep()
                cycles++
            }
            traceLoop()
        } catch (e: Throwable) {
            handleError("Trace tr Loop", e, e is AlignmentError || e is StoreError)
        }
    }
    internal fun runTrEnd() {
        try {
            tr.traceEnd()
            tr.traceStringStart()
            traceStringLoop()
        } catch (e: Throwable) {
            handleError("Trace Tr End", e, e is AlignmentError || e is StoreError)
        }
    }

    internal fun traceStringLoop() {
        try {
        var cycles = 0
        while (cycles < TIMEOUT_CYCLES) {
            if (!tr.traceStringStep()) {
                traceStringEnd()
                return
            }
        }
            traceStringLoop()
        } catch (e: Throwable) {
            handleError("Trace String Loop", e, e is AlignmentError || e is StoreError)
        }
    }

    internal fun traceStringEnd() {
        try {
            tr.traceStringEnd()
            Renderer.printConsole(tr.getString())
        } catch (e: Throwable) {
            handleError("Trace String End", e, e is AlignmentError || e is StoreError)
        }
    }

    /*@JsName("trace") fun trace() {
        //@todo make it so trace is better
        Renderer.setNameButtonSpinning("venusbackend.simulator-trace", true)
        Renderer.clearConsole()
        this.loadTraceSettings()
        window.setTimeout(Driver::traceStart, TIMEOUT_TIME)
    }*/
    internal fun traceStart() {
        try {
            tr.trace()
            traceString()
        } catch (e: Throwable) {
            handleError("Trace Start", e, e is AlignmentError || e is StoreError)
        }
    }
    internal fun traceString() {
        try {
            tr.traceString()
            Renderer.printConsole(tr.getString())
        } catch (e: Throwable) {
            handleError("Trace to String", e)
        }
    }
}