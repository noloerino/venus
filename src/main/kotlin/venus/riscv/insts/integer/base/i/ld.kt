package venus.riscv.insts.integer.base.i

import venus.riscv.insts.InstructionNotSupportedError
import venus.riscv.insts.dsl.LoadTypeInstruction

val ld = LoadTypeInstruction(
        name = "ld",
        opcode = 0b0000011,
        funct3 = 0b011,
//        load16 = Simulator::loadWordwCache
        load32 = { a, b ->
            throw InstructionNotSupportedError("LD is not supported by 32 bit systems!")
        }
//        load64 = Simulator::loadWordwCache
//        load128 = Simulator::loadWordwCache
)