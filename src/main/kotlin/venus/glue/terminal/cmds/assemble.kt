package venus.glue.terminal.cmds

import venus.assembler.Assembler
import venus.glue.terminal.Command
import venus.glue.terminal.Terminal
import venus.glue.vfs.VFSFile
import venus.glue.vfs.VFSObject
import venus.glue.vfs.VFSProgram
import venus.glue.vfs.VFSType

var assemble = Command(
        name = "assemble",
        execute = fun (args: MutableList<String>, t: Terminal, sudo: Boolean): String {
            if (args.size !in listOf(1, 2)) {
                return Command["assemble"].help
            }
            val filein = args[0]
            val programout = if (args.size == 2) args[1] else "a.out"
            val file = t.vfs.getObjectFromPath(filein)
            if (file === null) {
                return "assembler: Could not find file $filein"
            }
            if (file.type != VFSType.File) {
                return "assemble: $filein: Is a directory"
            }
            if (!file.contents.containsKey(VFSFile.innerTxt)) {
                return "assemble: $filein: COULD NOT FIND FILE CONTENTS!"
            }
            if (!VFSObject.isValidName(programout)) {
                return "assemble: $programout: Invalid name"
            }
            var msg = ""
            val (prog, errors, warnings) = Assembler.assemble(file.contents.get(VFSFile.innerTxt) as String, programout)
            if (errors.isNotEmpty()) {
                msg += "assemble: Could not assemble file! Here are the errors:"
                for (error in errors) {
                    msg += "\n" + error.toString()
                }
                return msg
            }
            if (warnings.isNotEmpty()) {
                msg += "assemble: Assembled file with a few warnings:"
                for (warning in warnings) {
                    msg += "\n" + warning.toString()
                }
            }
            val p = VFSProgram(programout, file.parent, prog)
            file.parent.addChild(p)
            return msg
        },
        tab = fun (args: MutableList<String>, t: Terminal, sudo: Boolean): ArrayList<String> {
            throw NotImplementedError()
            return ArrayList<String>()
        },
        help = "assemble: takes in two arguments: [text in] {[program out], a.out}"
)