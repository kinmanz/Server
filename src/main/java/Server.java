import java.io.*;
import java.lang.String;
import java.net.*;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
//import java.util.concurrent.ConcurrentHashMap;
import util.ConcurrentHashMap;
import com.google.protobuf.GeneratedMessage;


import com.sun.org.apache.xpath.internal.operations.*;
import communication.Protocol;
import communication.Protocol.*;

public class Server
{
    static int num = 0;
    static final int PORT_NUMBER = 44444;

    public static void main(String args[])
    {
        try(ServerSocket server = new ServerSocket(PORT_NUMBER);)
        {
            System.out.println("Server is started: ");

            while(true)
            {
                new TaskThread(server.accept());
                num++;
            }
        }
        catch(Exception e)
        {
            System.out.println("init server error: " + e);
        }
    }
}

class TaskThread extends Thread {
    //static
    static AtomicInteger initialId = new AtomicInteger(1);
    static ConcurrentHashMap<Integer, Calculation> taskMap = new ConcurrentHashMap<>();
//    static Map<Integer, Calculation> taskMap = new ConcurrentHashMap<Integer, Calculation>();
    static int curId;

    //no-static
    Socket socket;
    long requestId;
    String clientId;
    Calculation curTaskDescription;

    TaskThread(Socket socket)
    {
        this.socket = socket;
        setDaemon(true);
        setPriority(NORM_PRIORITY);
        start();
        System.out.println();
    }

    public void run()
    {
        try
        {
            ServerRequest request = getServerRequest();
            clientId = request.getClientId();
            requestId = request.getRequestId();
            System.out.println("clientId: " + clientId);
            System.out.println("requestId: " + requestId);

            if (request.hasSubmit()) {
                submitHandler(request.getSubmit());
            } else if (request.hasSubscribe()) {
                subscribeHandler(request.getSubscribe());
            } else if(request.hasList()) {
                listHandler();
            }

            socket.close();
        } catch(Exception e) {
            System.out.print("work error: ");
            e.printStackTrace();
            System.out.println();
        }
    }

    void listHandler() throws IOException{
        ServerResponse.Builder serverResponse = ServerResponse.newBuilder();
        serverResponse.setRequestId(requestId);

        ListTasksResponse.Builder listResponse = ListTasksResponse.newBuilder().setStatus(Protocol.Status.OK);
        listResponse.addAllTasks(getTasks());

        sendToClient(serverResponse.setListResponse(listResponse.build()).build());
    }

    List<Protocol.ListTasksResponse.TaskDescription> getTasks() {
        List<ListTasksResponse.TaskDescription> res = new LinkedList<>();
        for (Integer taskId: taskMap.keySet()) {
                Calculation task = taskMap.get(taskId);

                ListTasksResponse.TaskDescription.Builder description =
                        ListTasksResponse.TaskDescription.newBuilder();
                description.setClientId(task.clientId).setTaskId(taskId).setTask(task.task);
                if (task.isReady())
                {
                    description.setResult(task.getValue());
                }
                res.add(description.build());
        }
        return res;
    }


    void submitHandler(SubmitTask submitTask) throws IOException{

        //Вытакскиваем поля
        Task currentTask = submitTask.getTask();
        curTaskDescription = new Calculation(requestId, clientId, currentTask);
        curId = getNewId();
        taskMap.put(curId, curTaskDescription);

        //Тут могли встетиться ошибки тогда статус будет изменён
        checkSubmitTaskField(currentTask.getA());
        checkSubmitTaskField(currentTask.getB());
        checkSubmitTaskField(currentTask.getM());
        checkSubmitTaskField(currentTask.getP());

        //Отвечаем
//        Только в случае если поля валидны мы возвращаем статус OK
//        И производим вычисления иначе ERROR
        ServerResponse.Builder serverResponse = ServerResponse.newBuilder();
        serverResponse.setRequestId(requestId);

        SubmitTaskResponse.Builder submitTaskResponse = SubmitTaskResponse.newBuilder();
        submitTaskResponse.setSubmittedTaskId(curId);


        System.out.println("SubmitTaskResponse: ");
        System.out.println("submittedTaskId: " + curId);
        if (curTaskDescription.status == Status.Error) {
            submitTaskResponse.setStatus(Protocol.Status.ERROR);
            System.out.println("status: " + "ERROR");
        } else {
            submitTaskResponse.setStatus(Protocol.Status.OK);
            System.out.println("status: " + "OK");
        }

        sendToClient(serverResponse.setSubmitResponse(submitTaskResponse.build()).build());

        //Решаем задачу
        if (curTaskDescription.status != Status.Error) {
            long a = takeParamValue(currentTask.getA());
            long b = takeParamValue(currentTask.getB());
            long p = takeParamValue(currentTask.getP());
            long m = takeParamValue(currentTask.getM());
            long n = currentTask.getN();
            long res = task(a, b, p, m ,n);
            curTaskDescription.setValue(res);
        }
    }

    void subscribeHandler(Subscribe subscribe) throws IOException {
        int subscribeId = subscribe.getTaskId();
        ServerResponse.Builder serverResponse = ServerResponse.newBuilder();
        serverResponse.setRequestId(requestId);

        SubscribeResponse.Builder subscribeResponse = SubscribeResponse.newBuilder();

        //1) if Пытаемся подписать на задачу которой нет, или на задачу с ошибкой
        //2) else она существует подписываемся
        if (!taskMap.containsKey(subscribeId) || taskMap.get(subscribeId).status == Status.Error) {
            subscribeResponse.setStatus(Protocol.Status.ERROR);
            sendToClient(serverResponse.setSubscribeResponse(subscribeResponse).build());
        } else {
            long value = taskMap.get(subscribeId).getValue();
            subscribeResponse.setStatus(Protocol.Status.OK).setValue(value);
            sendToClient(serverResponse.setSubscribeResponse(subscribeResponse).build());
        }
    }

    void sendToClient(GeneratedMessage message) throws IOException{
        message.writeDelimitedTo(socket.getOutputStream());
    }

    //Поля уже проверены checkField function
    long takeParamValue(Task.Param param) {
        if (param.hasValue())
        {
            return param.getValue();
        } else {
            //(param.hasDependentTaskId())
            return taskMap.get(param.getDependentTaskId()).getValue();
        }
    }

    //проверка на валидность поля в случае подписки
    void checkSubmitTaskField(Task.Param param){
        if (param.hasDependentTaskId()) {
            //Если мапа не содержит ключ, или содержит, но он ссылается на себя самого
            if (param.getDependentTaskId() == curId) {
                curTaskDescription.status = Status.Error;
                return;
            }
            if  (!taskMap.containsKey(param.getDependentTaskId()) || taskMap.get(param.getDependentTaskId()).status == Status.Error) {
                //если пытаемся взять задачу с ERROR
                curTaskDescription.status = Status.Error;
            }
        } else if(!param.hasValue()) {
            //Если и значения тоже нет
            curTaskDescription.status = Status.Error;
        }
    }


    long task(long a, long b, long p, long m, long n)
    {
        if (m == 0) {
            curTaskDescription.setStatus(Status.Error);
            return 1;
        }

        while (n-- > 0)
        {
            b = (a * p + b) % m;
            a = b;
        }

        return a;
    }

    ServerRequest getServerRequest() throws IOException {
        return ServerRequest.parseDelimitedFrom(socket.getInputStream());
    }

    int getNewId() {
        return initialId.getAndIncrement();
    }
}
//информация о каждой задачи
class Calculation {

    Status status = Status.notReady;
    final Object GoldenBell = new Object();
    volatile long value;

    //status information
    long requestId;
    String clientId;
    final Thread ownThread = Thread.currentThread();
    final Task task;

    Calculation(long requestId, String clientId, Task task) {
        this.requestId = requestId;
        this.clientId = clientId;
        this.task = task;
    }

    long getValue () {
        System.out.println("before");
        synchronized (GoldenBell) {
            System.out.println("after");
            try {
                while (!isReady()) {
                    GoldenBell.wait();
                }
            } catch (InterruptedException e) {
                System.out.println("getValue: " + e);
            }
            return value;
        }
    }

    void setStatus(Status status) {
        synchronized (GoldenBell) {
            this.status = status;
            if (isReady()) {
                GoldenBell.notifyAll();
            }
        }
    }

    void setValue(long value) {
        this.value = value;
        setStatus(Status.Ready);
    }

    boolean isReady(){
        return status == Status.Ready;
    }
}

enum Status{
    Ready, notReady, Error;
}
