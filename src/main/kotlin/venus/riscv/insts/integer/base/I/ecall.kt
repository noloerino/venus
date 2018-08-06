package venus.riscv.insts.integer.base.i

import venus.glue.Renderer
import venus.riscv.InstructionField
import venus.riscv.MemorySegments
import venus.riscv.insts.dsl.Instruction
import venus.riscv.insts.dsl.disasms.RawDisassembler
import venus.riscv.insts.dsl.formats.FieldEqual
import venus.riscv.insts.dsl.formats.InstructionFormat
import venus.riscv.insts.dsl.impls.NoImplementation
import venus.riscv.insts.dsl.impls.RawImplementation
import venus.riscv.insts.dsl.parsers.DoNothingParser
import venus.simulator.Simulator

val ecall = Instruction(
        name = "ecall",
        format = InstructionFormat(4,
                listOf(FieldEqual(InstructionField.ENTIRE, 0b000000000000_00000_000_00000_1110011))
        ),
        parser = DoNothingParser,
        impl16 = NoImplementation,
        impl32 = RawImplementation { mcode, sim ->
            val whichCall = sim.getReg(10)
            when (whichCall) {
                1 -> printInteger(sim)
                4 -> printString(sim)
                9 -> sbrk(sim)
                10 -> exit(sim)
                11 -> printChar(sim)
                17 -> exitWithCode(sim)
                else -> Renderer.printConsole("Invalid ecall $whichCall")
            }
            if (!(whichCall == 10 || whichCall == 17)) {
                sim.incrementPC(mcode.length)
            }
        },
        impl64 = NoImplementation,
        impl128 = NoImplementation,
        disasm = RawDisassembler { "ecall" }
)

private fun printInteger(sim: Simulator) {
    val arg = sim.getReg(11)
    sim.ecallMsg = arg.toString()
    Renderer.printConsole(arg)
}

private fun printString(sim: Simulator) {
    var arg = sim.getReg(11)
    var c = sim.loadByte(arg)
    arg++
    while (c != 0) {
        sim.ecallMsg += c.toChar()
        Renderer.printConsole(c.toChar())
        c = sim.loadByte(arg)
        arg++
    }
}

private fun sbrk(sim: Simulator) {
    val bytes = sim.getReg(11)
    if (bytes < 0) return
    sim.setReg(10, sim.getHeapEnd())
    sim.addHeapSpace(bytes)
}

private fun exit(sim: Simulator) {
    sim.setPC(MemorySegments.STATIC_BEGIN)
    sim.ecallMsg = "exiting the simulator"
}

private fun printChar(sim: Simulator) {
    val arg = sim.getReg(11)
    sim.ecallMsg = (arg.toChar()).toString()
    Renderer.printConsole(arg.toChar())
}

private fun exitWithCode(sim: Simulator) {
    sim.setPC(MemorySegments.STATIC_BEGIN)
    val retVal = sim.getReg(11)
    sim.ecallMsg = "Exited with error code $retVal"
    Renderer.printConsole("Exited with error code $retVal\n")
}