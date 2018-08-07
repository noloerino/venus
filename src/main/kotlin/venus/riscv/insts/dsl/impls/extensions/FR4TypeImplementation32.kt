package venus.riscv.insts.dsl.impls.extensions

import venus.riscv.InstructionField
import venus.riscv.MachineCode
import venus.riscv.insts.dsl.impls.InstructionImplementation
import venus.simulator.Simulator

/**
 * Created by thaum on 8/6/2018.
 */
class FR4TypeImplementation32(private val eval: (Float, Float, Float) -> Float) : InstructionImplementation {
    override operator fun invoke(mcode: MachineCode, sim: Simulator) {
        val rs1 = mcode[InstructionField.RS1]
        val rs2 = mcode[InstructionField.RS2]
        val rs3 = mcode[InstructionField.RS3]
        val rd = mcode[InstructionField.RD]
        val vrs1 = sim.getFReg(rs1)
        val vrs2 = sim.getFReg(rs2)
        val vrs3 = sim.getFReg(rs3)
        sim.setFReg(rd, eval(vrs1, vrs2, vrs3))
        sim.incrementPC(mcode.length)
    }
}